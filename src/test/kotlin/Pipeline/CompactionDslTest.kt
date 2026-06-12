package com.TTT.Pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the `compaction { }` DSL block on PumpStationBuilder. Verifies the
 * block captures configuration and applies it to the built station via
 * PumpStationBuilder.build().
 */
class CompactionDslTest
{
    @Test
    fun testCompactionDslAppliesConfiguration()
    {
        val station = pumpStation("dsl-test") {
            dispatchAgent = Pipeline()
            path("p1") {}
            compaction {
                strategy = PumpStationCompactionStrategy.Chunked
                fanout = ChunkFanoutMode.Parallel
                maxAttempts = 3
                chunkTokenBudget = 1500
                maxBackups = 5
                hybridWholeHeadroom = 0.4
            }
        }
        assertEquals(PumpStationCompactionStrategy.Chunked, station.compactionStrategyInternal)
        assertEquals(ChunkFanoutMode.Parallel, station.compactionFanoutModeInternal)
        assertEquals(3, station.maxCompactionAttemptsInternal)
        assertEquals(1500, station.chunkTokenBudgetInternal)
        assertEquals(5, station.maxCompactionBackupsInternal)
        assertEquals(0.4, station.hybridWholeHeadroomInternal)
    }

    @Test
    fun testCompactionDslDefaultsAreNullSafe()
    {
        // No compaction { } block configured; the built station uses the PumpStation
        // defaults (Whole, 0.8 threshold, Sequential, 2 attempts, etc.).
        val station = pumpStation("dsl-defaults") {
            dispatchAgent = Pipeline()
            path("p1") {}
        }
        assertEquals(PumpStationCompactionStrategy.Whole, station.compactionStrategyInternal)
        assertEquals(ChunkFanoutMode.Sequential, station.compactionFanoutModeInternal)
        assertEquals(2, station.maxCompactionAttemptsInternal)
    }

    @Test
    fun testCompactionDslPrePruneAndRolledBackHooks()
    {
        // The pre-prune transform and rollback DITL hook are captured by the
        // DSL block and applied to the built station.
        var prePruneRan = false
        var rolledBackRan = false
        val station = pumpStation("dsl-hooks") {
            dispatchAgent = Pipeline()
            path("p1") {}
            compaction {
                prePrune { turns, _ ->
                    prePruneRan = true
                    turns
                }
                onRolledBack { backup, _, _ ->
                    rolledBackRan = true
                    null
                }
            }
        }
        // The pre-prune transform is captured; verify it was registered.
        assertNotNull(station.prePruneTransformInternal)
        // The rollback DITL hook is captured.
        assertNotNull(station.compactionRolledBackFunctionInternal)
        // Drive the pre-prune via the existing prePruneForCompaction helper.
        runBlocking {
            station.prePruneForCompaction(emptyList())
        }
        assertTrue(prePruneRan)
    }
}
