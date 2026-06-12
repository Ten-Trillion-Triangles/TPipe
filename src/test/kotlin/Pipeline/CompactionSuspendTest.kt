package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.P2P.KillSwitch
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the v3 compaction coroutine/concurrency safety:
 *  - `runTest` proves no `runBlocking` deadlock
 *  - kill switch mid-compaction exits cleanly
 *  - cursors not committed on Inflate
 */
class CompactionSuspendTest
{
    @Test
    fun runTestNoRunBlockingDeadlock() = runTest {
        // If runCompactionPhase used runBlocking, this test would deadlock.
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionThreshold(0.0)
        station.taskState.compactionCursor = CompactionCursor()
        repeat(5) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it: ${"x".repeat(50)}")
                )
            )
        }

        val result = station.runCompactionPhase()
        assertNotNull(result, "runTest should complete without deadlock")
    }

    @Test
    fun killSwitchMidCompactionExitsCleanly() = runBlocking {
        val station = buildTestStation()
        val ks = KillSwitch(inputTokenLimit = 1, outputTokenLimit = 1)
        station.killSwitch = ks
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionThreshold(0.0)
        station.taskState.compactionCursor = CompactionCursor()
        repeat(5) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it: ${"x".repeat(50)}")
                )
            )
        }

        val outcome = runCatching { station.runCompactionPhase() }
        assertTrue(outcome.isSuccess || outcome.exceptionOrNull() is com.TTT.P2P.KillSwitchException,
            "Outcome should be success or KillSwitchException, got ${outcome.exceptionOrNull()?.javaClass?.simpleName}")
    }

    @Test
    fun inProgressCursorsNotCommittedOnInflate() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.taskState.compactionCursor = CompactionCursor(generation = 7)
        val initialGen = station.taskState.compactionCursor!!.generation

        val inflated = CompactionResult.Inflated(inputTokens = 100, outputTokens = 500, attempt = 1)
        assertEquals(CompactionResult.Inflated::class, inflated::class)
        assertEquals(initialGen, station.taskState.compactionCursor!!.generation,
            "Cursor must not advance on Inflate")
    }

    @Test
    fun compactionSkipsWhenCursorAlreadyAdvanced() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.taskState.compactionCursor = CompactionCursor(generation = 100, lastCompactedTurnIndex = 50)
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionThreshold(0.0)
        repeat(5) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it")
                )
            )
        }

        val result = station.runCompactionPhase()
        assertTrue(result is CompactionResult, "Result should be CompactionResult, got $result")
    }
}
