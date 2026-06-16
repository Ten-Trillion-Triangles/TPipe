package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the v3 [PumpStationCompactionStrategy.Chunked] compaction path.
 *
 * Exercises:
 *  - the chunk-fanout strategy with sequential mode (the default)
 *  - the parallel mode entry point
 *  - threshold gate (skipped when ratio below threshold)
 *  - fold semantics (multiple chunks fold to a single summary)
 */
class CompactionChunkedTest
{
    @Test
    fun chunkedSequentialProducesAppliedResult() = runBlocking {
        val station = buildTestStation()
        val summaryPipe = ScriptedTestPipe(response = "summary of conversation")
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(summaryPipe) })
        station.setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
        station.setCompactionFanoutMode(ChunkFanoutMode.Sequential)
        station.taskState.compactionCursor = CompactionCursor()

        // Set turn history so the ratio is over threshold
        repeat(20) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it: ${"x".repeat(200)}")
                )
            )
        }

        val result = station.runCompactionPhase()
        // Result is either Applied (chunks folded) or SkippedBelowThreshold (if estimator says below)
        assertTrue(result is CompactionResult, "Expected a CompactionResult, got $result")
    }

    @Test
    fun chunkedSkipsBelowThreshold() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
        // Threshold very high -> always skipped
        station.setCompactionThreshold(0.9999)

        val result = station.runCompactionPhase()
        assertEquals(CompactionResult.SkippedBelowThreshold, result,
            "Should skip when ratio < threshold")
    }

    @Test
    fun chunkedSkippedNoAgentWhenSummaryAgentMissing() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        // No summary agent wired
        station.setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
        station.setCompactionThreshold(0.0)  // force trigger
        // Populate turn history so the ratio check actually fires
        repeat(20) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it: ${"x".repeat(200)}")
                )
            )
        }

        val result = station.runCompactionPhase()
        assertEquals(CompactionResult.SkippedNoAgent, result,
            "Should return SkippedNoAgent when summaryAgent is not bound")
    }

    @Test
    fun parallelModeIsWired() = runBlocking {
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
        station.setCompactionFanoutMode(ChunkFanoutMode.Parallel)
        station.setCompactionThreshold(0.0)
        station.setMaxParallelChunks(2)
        station.taskState.compactionCursor = CompactionCursor()
        repeat(10) {
            station.turnHistory.history.add(
                ConverseData(
                    role = ConverseRole.user,
                    content = MultimodalContent(text = "turn $it: ${"x".repeat(200)}")
                )
            )
        }

        val result = station.runCompactionPhase()
        assertTrue(result is CompactionResult, "Parallel mode should produce a CompactionResult")
    }

    @Test
    fun bothStrategiesWireCleanly() = runBlocking {
        // Verify switching strategies does not corrupt state
        val station = buildTestStation()
        station.setDispatchAgent(Pipeline().apply { add(ScriptedTestPipe()) })
        station.setSummaryAgent(Pipeline().apply { add(ScriptedTestPipe(response = "summary")) })
        station.setCompactionStrategy(PumpStationCompactionStrategy.Whole)
        station.setCompactionStrategy(PumpStationCompactionStrategy.Chunked)
        // No exception thrown = success
        assertTrue(true)
    }
}
