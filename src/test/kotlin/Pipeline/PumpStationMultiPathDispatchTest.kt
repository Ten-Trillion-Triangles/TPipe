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
            pathExecutionShape = PathExecutionShape.SinglePath
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

    @Test
    fun multiPathDispatchPromptMentionsListShape()
    {
        val prompt = DEFAULT_DISPATCH_PROMPT_MULTI
        assert(prompt.contains("paths")) { "multi-path prompt must mention 'paths' key" }
        assert(prompt.contains("PathRequestList")) { "multi-path prompt must name the type" }
        assert(prompt.contains("batchRationale")) { "multi-path prompt must document batchRationale" }
    }

    @Test
    fun buildDispatchSystemPromptBranchesOnShape()
    {
        val singleStation = pumpStation("single-shape") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.SinglePath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        val singlePrompt = singleStation.buildDispatchSystemPrompt()
        assert(singlePrompt.contains("PathRequest")) {
            "SinglePath prompt must reference PathRequest"
        }
        assert(!singlePrompt.contains("PathRequestList")) {
            "SinglePath prompt must NOT reference PathRequestList"
        }

        val multiStation = pumpStation("multi-shape") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.MultiPath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        val multiPrompt = multiStation.buildDispatchSystemPrompt()
        assert(multiPrompt.contains("PathRequestList")) {
            "MultiPath prompt must reference PathRequestList"
        }
    }

    @Test
    fun parseDispatchOutputMultiExtractsList()
    {
        val station = pumpStation("multi-parse") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.MultiPath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        val llmOutput = com.TTT.Pipe.MultimodalContent(text = """
            {
              "paths": [
                {"pathName": "noop", "pathSchema": "{}", "pathSelectionRationale": "first"},
                {"pathName": "noop", "pathSchema": "{}", "pathSelectionRationale": "second"}
              ],
              "batchRationale": "Independent reads, parallelize."
            }
        """.trimIndent())
        val result = station.parseDispatchOutputMulti(llmOutput)
        assertEquals(2, result?.paths?.size)
        assertEquals("noop", result?.paths?.get(0)?.pathName)
        assertEquals("Independent reads, parallelize.", result?.batchRationale)
    }

    @Test
    fun parseDispatchOutputMultiRejectsSingleShape()
    {
        val station = pumpStation("multi-rejects-single") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.MultiPath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        val llmOutput = com.TTT.Pipe.MultimodalContent(text = """{"pathName": "noop", "pathSchema": "{}"}""")
        val result = station.parseDispatchOutputMulti(llmOutput)
        assertEquals(null, result)
    }

    @Test
    fun parseDispatchOutputMultiReturnsNullOnGarbage()
    {
        val station = pumpStation("multi-garbage") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.MultiPath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        val result = station.parseDispatchOutputMulti(com.TTT.Pipe.MultimodalContent(text = "not json at all"))
        assertEquals(null, result)
    }

    @Test
    fun dslPathExecutionShapeAssignmentApplies()
    {
        val station = pumpStation("dsl-multi") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            pathExecutionShape = PathExecutionShape.MultiPath
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        assertEquals(PathExecutionShape.MultiPath, station.getPathExecutionShape())
    }

    @Test
    fun dslDefaultIsSinglePath()
    {
        val station = pumpStation("dsl-default") {
            judgeAgent = Pipeline()
            dispatchAgent = Pipeline()
            path("noop") {
                description = "noop"
                setExecutionFunction { _, _, _, _ -> com.TTT.Pipe.MultimodalContent(text = "ok") }
            }
        }
        assertEquals(PathExecutionShape.SinglePath, station.getPathExecutionShape())
    }
}