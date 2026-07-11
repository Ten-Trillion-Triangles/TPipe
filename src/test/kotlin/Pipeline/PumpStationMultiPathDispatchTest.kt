package com.TTT.Pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class PumpStationMultiPathDispatchTest
{
    @Test
    fun pathExecutionShapeEnumHasExpectedValues()
    {
        assertEquals(2, PathExecutionShape.entries.size)
        assertNotEquals(PathExecutionShape.SinglePath, PathExecutionShape.MultiPath)
    }

    @Test
    fun pathExecutionShapeDefaultIsSinglePath()
    {
        val station = pumpStation("default-shape") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            path("noop") {
                description = "no-op test path"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        assertEquals(PathExecutionShape.SinglePath, station.getPathExecutionShape())
        assertNotNull(station)
    }

    @Test
    fun pathRequestListSerializesAndDeserializes()
    {
        val original = PathRequestList(
            paths = listOf(
                PathRequest(pathName = "gather", pathSchema = "{}", pathSelectionRationale = "first"),
                PathRequest(pathName = "analyze", pathSchema = "{}", pathSelectionRationale = "second")
            ),
            batchRationale = "Independent reads, parallelize."
        )
        val text = com.TTT.Util.serialize(original)
        val roundTripped: PathRequestList = com.TTT.Util.deserialize(text) ?: error("deserialize failed")
        assertEquals(original, roundTripped)
    }

    @Test
    fun pathRequestListDefaultsAreSensible()
    {
        val empty = PathRequestList()
        assertEquals(emptyList<PathRequest>(), empty.paths)
        assertEquals(null, empty.batchRationale)
    }

    @Test
    fun pathBatchStartedEventCarriesPathNames()
    {
        val event = PathBatchStarted(
            runId = "test-run",
            turnIndex = 0,
            pathNames = listOf("gather", "analyze"),
            batchRationale = "parallel read"
        )
        assertEquals(listOf("gather", "analyze"), event.pathNames)
        assertEquals(PumpStationPhase.Dispatch, event.phase)
        assertEquals("test-run", event.runId)
    }

    @Test
    fun pathBatchCompletedCarriesCounts()
    {
        val event = PathBatchCompleted(
            runId = "test-run",
            turnIndex = 0,
            totalPaths = 3,
            succeededPaths = 2,
            failedPaths = 1
        )
        assertEquals(3, event.totalPaths)
        assertEquals(2, event.succeededPaths)
        assertEquals(1, event.failedPaths)
    }

    @Test
    fun pathBatchFailedCarriesRepairAttempts()
    {
        val event = PathBatchFailed(
            runId = "test-run",
            turnIndex = 0,
            errorMessage = "JSON repair exhausted",
            repairAttempts = 2
        )
        assertEquals(2, event.repairAttempts)
        assertEquals("JSON repair exhausted", event.errorMessage)
    }
}