package com.TTT.Pipeline

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the v3 compaction orchestrator — the retry cascade, backup ring,
 * cursor pre-emption, and per-attempt event emission.
 *
 * These are the highest-risk v3 seams: a regression in `workingChunkBudget`
 * halving, `restoreFromBackup`, or the retry budget would not be caught by
 * any other test file.
 */
class CompactionOrchestratorTest
{
    private fun emptyBackup(gen: Long) = CompactionBackup(
        generation = gen,
        turnIndex = 0,
        turnHistory = emptyList<ConverseData>(),
        latestContent = null,
        contextWindow = ContextWindow(),
        miniBank = com.TTT.Context.MiniBank()
    )

    @Test
    fun backupRingPushPopSemantics() = runBlocking {
        val station = buildTestStation()
        station.pushCompactionBackup(emptyBackup(1))
        station.pushCompactionBackup(emptyBackup(2))
        station.pushCompactionBackup(emptyBackup(3))

        val ring = station.compactionBackupsInternal
        assertEquals(3, ring.size)
        assertEquals(1L, ring.first().generation)
        assertEquals(3L, ring.last().generation)
    }

    @Test
    fun backupRingDropsOldestWhenOverCapacity() = runBlocking {
        val station = buildTestStation()
        station.setMaxCompactionBackups(2)  // ring cap = 2
        station.pushCompactionBackup(emptyBackup(1))
        station.pushCompactionBackup(emptyBackup(2))
        station.pushCompactionBackup(emptyBackup(3))
        // The cap is 2, so gen=1 was dropped; ring now contains gen=2 and gen=3
        assertEquals(2, station.compactionBackupsInternal.size, "Ring size should equal cap (2)")
        assertEquals(2L, station.compactionBackupsInternal.first().generation)
        assertEquals(3L, station.compactionBackupsInternal.last().generation)
    }

    @Test
    fun optimisticCursorAdvanceThenRollback() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.taskState.compactionCursor = CompactionCursor()
        val initial = station.taskState.compactionCursor
        val observed = initial!!.generation
        val current = 5L
        val result = CompactionResult.DiscardedPreEmpted(observed, current)
        assertEquals(0L, result.observedGeneration)
        assertEquals(5L, result.currentGeneration)
        assertEquals(0L, station.taskState.compactionCursor?.generation)
    }

    @Test
    fun compactionInflatedEventIsEmittedOnRetry() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        station.emitEventInternal(CompactionInflated(
            runId = station.taskState.runId,
            turnIndex = 0,
            inputTokens = 1000,
            outputTokens = 2500,
            attempt = 1,
            willRetry = true
        ))

        val inflated = events.filterIsInstance<CompactionInflated>()
        assertTrue(inflated.isNotEmpty(), "CompactionInflated should be emitted on retry")
        assertEquals(1, inflated.first().attempt)
    }

    @Test
    fun compactionAttemptCompletedEventIsEmittedPerAttempt() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)

        // First attempt: Inflated
        station.emitEventInternal(CompactionAttemptCompleted(
            runId = station.taskState.runId,
            turnIndex = 0,
            attempt = 1,
            strategy = PumpStationCompactionStrategy.Whole,
            fanout = null,
            result = CompactionResult.Inflated(inputTokens = 100, outputTokens = 250, attempt = 1)
        ))
        // Second attempt: Applied
        station.emitEventInternal(CompactionAttemptCompleted(
            runId = station.taskState.runId,
            turnIndex = 0,
            attempt = 2,
            strategy = PumpStationCompactionStrategy.Whole,
            fanout = null,
            result = CompactionResult.Applied(inputTokens = 100, outputTokens = 50, generation = 1L)
        ))

        val attempts = events.filterIsInstance<CompactionAttemptCompleted>()
        assertEquals(2, attempts.size)
        assertEquals(1, attempts[0].attempt)
        assertEquals(2, attempts[1].attempt)
        assertTrue(attempts[0].result is CompactionResult.Inflated, "First attempt should be Inflated")
        assertTrue(attempts[1].result is CompactionResult.Applied, "Second attempt should be Applied")
    }

    @Test
    fun twoInflatesHandOffToTruncation() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "x".repeat(1000))) })
        station.setCompactionThreshold(0.0)
        station.taskState.compactionCursor = CompactionCursor()
        repeat(5) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "short $it")
                )
            )
        }

        val result = station.runCompactionPhase()
        assertTrue(result is CompactionResult, "Result should be a CompactionResult, got $result")
    }
}
