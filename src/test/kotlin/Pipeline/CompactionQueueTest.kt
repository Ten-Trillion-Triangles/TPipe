package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the v3 compaction cursor, backup ring, and orchestrator pre-emption
 * semantics. Verifies the "ahead compaction already covered this" discard path
 * works end-to-end.
 */
class CompactionQueueTest
{
    private fun mkTurn(text: String, role: ConverseRole = ConverseRole.user): ConverseData
    {
        return ConverseData(role = role, content = MultimodalContent(text = text))
    }

    private fun stationWithTurnsAndAgent(vararg texts: String): Pair<PumpStation, MockP2PAgent>
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        texts.forEach { station.turnHistory.add(mkTurn(it)) }
        val summaryAgent = MockP2PAgent(script = listOf(MultimodalContent(text = "summary-result")))
        station.setSummaryAgent(summaryAgent)
        // Force the threshold low so compaction is eligible.
        station.setCompactionThreshold(0.0)
        return station to summaryAgent
    }

    @Test
    fun testGenerationAdvancesOnEachCompaction()
    {
        val (station, _) = stationWithTurnsAndAgent("turn 1", "turn 2")
        assertEquals(0L, station.compactionCursorInternal.generation)
        runBlocking { station.runCompactionAttempt(PumpStationCompactionStrategy.Whole, 1, station.chunkTokenBudgetInternal) }
        assertEquals(1L, station.compactionCursorInternal.generation)
    }

    @Test
    fun testCursorAdvancesOnApply()
    {
        val (station, _) = stationWithTurnsAndAgent("turn 1", "turn 2")
        station.taskState.turnIndex = 7
        runBlocking { station.runCompactionAttempt(PumpStationCompactionStrategy.Whole, 1, station.chunkTokenBudgetInternal) }
        val cursor = station.compactionCursorInternal
        assertEquals(7, cursor.lastCompactedTurnIndex)
        assertEquals(PumpStationCompactionStrategy.Whole, cursor.lastCompactionStrategy)
    }

    @Test
    fun testSecondArrivalDiscardsWork()
    {
        // The strategy function checks the cursor CAS at two points: (1) inside
        // the summaryMutex before invoking the LLM, and (2) again after the LLM
        // returns. To exercise the second check, we use a custom summary agent
        // whose executeLocal() bumps the cursor as a side effect, simulating a
        // concurrent compaction that committed while the LLM was in flight.
        val station = PumpStation().setDispatchAgent(Pipeline())
        // 200 chars of input so the small "ok" summary is well below the input.
        station.turnHistory.add(mkTurn("a".repeat(200)))
        val baseAgent = MockP2PAgent(script = listOf(MultimodalContent(text = "ok")))
        // Wrap with a side-effect: bump the cursor during the LLM call.
        val racingAgent = object : com.TTT.P2P.P2PInterface
        {
            override var killSwitch: com.TTT.P2P.KillSwitch? = null
            override suspend fun executeLocal(content: com.TTT.Pipe.MultimodalContent): com.TTT.Pipe.MultimodalContent
            {
                // Simulate a concurrent compaction that committed during this LLM call.
                station.compactionCursorWrite = station.compactionCursorWrite.copy(
                    generation = station.compactionCursorWrite.generation + 100
                )
                return baseAgent.executeLocal(content)
            }
            override suspend fun executeP2PRequest(request: com.TTT.P2P.P2PRequest): com.TTT.P2P.P2PResponse? = null
            override fun setParentInterface(parent: com.TTT.P2P.P2PInterface) {}
            override fun getParentP2PInterface(): com.TTT.P2P.P2PInterface? = null
            override fun getPaths(): String = ""
            override fun setTokenBudgetRecursive(budget: com.TTT.Pipe.TokenBudgetSettings) {}
            override fun getTokenBudgetSettings(): com.TTT.Pipe.TokenBudgetSettings? = null
            override fun setPipeSettingsRecursively(settings: com.TTT.Structs.PipeSettings) {}
            override suspend fun P2PInit() {}
        }
        station.setSummaryAgent(racingAgent)
        station.setCompactionThreshold(0.0)

        val result = runBlocking { station.runCompactionAttempt(PumpStationCompactionStrategy.Whole, 1, station.chunkTokenBudgetInternal) }
        // The first CAS check inside the strategy (before the LLM call) still sees
        // the captured generation, so the LLM call is made. The second CAS check
        // (after the LLM call) sees the cursor has moved (the racing agent bumped
        // it during the LLM call), so the result is DiscardedPreEmpted and no
        // mutation is applied to turnHistory.
        assertTrue(result is CompactionResult.DiscardedPreEmpted, "expected DiscardedPreEmpted, got $result")
    }

    @Test
    fun testInflatedRestoresBackup()
    {
        // Summary agent returns a string that is *longer* than the input. The orchestrator
        // should treat this as Inflated, restore the most recent backup, and (since this is
        // a single attempt) eventually hand off to truncation.
        val station = PumpStation().setDispatchAgent(Pipeline())
        // Long input: ~100 tokens of content.
        repeat(20) { station.turnHistory.add(mkTurn("a".repeat(80))) }
        val bigResponse = MultimodalContent(text = "x".repeat(2000))  // way longer
        station.setSummaryAgent(MockP2PAgent(script = listOf(bigResponse)))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(1)

        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        val result = runBlocking { station.runCompactionPhase() }
        assertTrue(result is CompactionResult.HandedOffToTruncation, "expected handoff, got $result")
        // Verify a RolledBack event was emitted (the orchestrator restored the backup
        // before deciding to hand off).
        assertTrue(events.any { it is CompactionRolledBack }, "expected CompactionRolledBack event")
        // Verify a HandedOffToTruncation event was emitted.
        assertTrue(events.any { it is CompactionHandedOffToTruncation }, "expected HandedOffToTruncation event")
    }

    @Test
    fun testRetryScopesDownToChunked()
    {
        // First attempt Whole returns Inflated; retry with Chunked should also return
        // Inflated (the summary agent is mocked to always inflate), but the orchestrator
        // should emit two CompactionAttemptCompleted events.
        val station = PumpStation().setDispatchAgent(Pipeline())
        repeat(20) { station.turnHistory.add(mkTurn("a".repeat(80))) }
        val bigResponse = MultimodalContent(text = "x".repeat(2000))
        station.setSummaryAgent(MockP2PAgent(script = listOf(bigResponse)))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(2)
        station.setChunkTokenBudget(40)
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        val result = runBlocking { station.runCompactionPhase() }
        // Both attempts inflate; the second is the last, so the result is handoff.
        assertTrue(result is CompactionResult.HandedOffToTruncation)
        // Two attempt events emitted.
        val attemptEvents = events.filterIsInstance<CompactionAttemptCompleted>()
        assertEquals(2, attemptEvents.size)
        // First attempt: Whole. Second attempt: Chunked (downgraded).
        assertEquals(PumpStationCompactionStrategy.Whole, attemptEvents[0].strategy)
        assertEquals(PumpStationCompactionStrategy.Chunked, attemptEvents[1].strategy)
    }

    @Test
    fun testHandoffContinuesHarness()
    {
        // After handoff, the harness's lastError is CompactionInflated, but the
        // turnHistory is truncated (oldest half removed) so the next turn can run.
        val station = PumpStation().setDispatchAgent(Pipeline())
        repeat(10) { i -> station.turnHistory.add(mkTurn("turn $i")) }
        val bigResponse = MultimodalContent(text = "x".repeat(2000))
        station.setSummaryAgent(MockP2PAgent(script = listOf(bigResponse)))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(1)

        val result = runBlocking { station.runCompactionPhase() }
        assertTrue(result is CompactionResult.HandedOffToTruncation)
        // 10 turns -> drop 5 -> 5 turns left.
        assertEquals(5, station.turnHistory.history.size)
        // taskState.lastError is set so the finalization phase can emit HarnessFailed.
        assertEquals(PumpStationError.CompactionInflated, station.taskState.lastError)
    }

    @Test
    fun testKillSwitchNotInvolvedInCompactionBlowout()
    {
        // The kill switch is an independent cost-control system. The compaction
        // failure cascade must NOT trip it. Set up a kill switch whose onTripped
        // callback records a flag, run a full compaction blowout, verify the
        // flag is still false.
        var tripFired = false
        val ks = com.TTT.P2P.KillSwitch(
            inputTokenLimit = null,
            outputTokenLimit = null,
            onTripped = { ctx -> tripFired = true; throw com.TTT.P2P.KillSwitchException(ctx) }
        )
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.killSwitch = ks
        repeat(10) { station.turnHistory.add(mkTurn("a".repeat(80))) }
        station.setSummaryAgent(MockP2PAgent(script = listOf(MultimodalContent(text = "x".repeat(2000)))))
        station.setCompactionThreshold(0.0)
        station.setMaxCompactionAttempts(1)

        runBlocking { station.runCompactionPhase() }

        assertEquals(false, tripFired)
    }

    @Test
    fun testBackupRingBufferDropsOldest()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setMaxCompactionBackups(2)
        repeat(5) { i ->
            val backup = CompactionBackup(
                generation = i.toLong(),
                turnIndex = i,
                turnHistory = listOf(mkTurn("backup $i")),
                latestContent = null,
                contextWindow = station.contextWindow.copy(),
                miniBank = station.miniBank.copy()
            )
            station.pushCompactionBackup(backup)
        }
        // Ring size capped at 2; oldest 3 dropped.
        val ring = station.compactionBackupsInternal
        assertEquals(2, ring.size)
        assertEquals(3L, ring.first().generation)
        assertEquals(4L, ring.last().generation)
    }
}
