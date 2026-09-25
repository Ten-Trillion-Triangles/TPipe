package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.BinaryContent
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.Enums.PumpStationHistoryTransport
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.reflect.KFunction
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicReference

private const val OVERSIZED_PCP_FUNCTION = "oversizedPcpPathOutput"
private const val OVERSIZED_PCP_TERMINATE_FUNCTION = "oversizedPcpTerminatingPathOutput"
private val OVERSIZED_PCP_OUTPUT = "large PCP result ".repeat(2_000)
private val PCP_DISPATCH_ARGUMENT = AtomicReference<String?>(null)

private fun oversizedPcpContent(): MultimodalContent = MultimodalContent(text = OVERSIZED_PCP_OUTPUT).apply {
    addBinary(byteArrayOf(1, 2, 3), "image/png", "pcp-result.png")
}

/** PCP function returning oversized content and signaling a successful pipeline pass. */
fun oversizedPcpPathOutput(marker: String): MultimodalContent = oversizedPcpContent().apply {
    PCP_DISPATCH_ARGUMENT.set(marker)
    passPipeline = true
}

/** PCP function returning oversized content and signaling pipeline termination. */
fun oversizedPcpTerminatingPathOutput(marker: String): MultimodalContent = oversizedPcpContent().apply {
    PCP_DISPATCH_ARGUMENT.set(marker)
    terminatePipeline = true
}

/** Covers oversized PCP PathObject output lifecycle at the PumpStation boundary. */
class PumpStationOversizedPcpPathOutputTest
{
    /** Stashes native PCP output while retaining the pass signal and compact references. */
    @Test
    fun oversizedPcpPathOutputIsStashedAndReplacedInLatestContentAndHistories()
    {
        FunctionRegistry.clear()
        try
        {
            runBlocking {
                val (station, events) = buildOversizedPcpStation(
                    OVERSIZED_PCP_FUNCTION,
                    ::oversizedPcpPathOutput,
                    transformPathResult = true
                )
                station.P2PInit()

                val turnResult = station.runTurn()

                assertTrue(events.any { it is PathCompleted }, events.joinToString { event ->
                    if (event is PathFailed) "${event::class.simpleName}:${event.error}:${event.errorMessage}"
                    else event::class.simpleName.orEmpty()
                })
                assertEquals("dispatch-argument", PCP_DISPATCH_ARGUMENT.get())
                assertEquals(TurnResult.Halt(PumpStationExitReason.PassSignal), turnResult)
                assertStashedPcpOutputIsCompact(station, expectPassSignal = true)
            }
        }
        finally
        {
            FunctionRegistry.clear()
        }
    }

    /** Stashing does not hide the path result's terminate signal from the parent station. */
    @Test
    fun oversizedPcpTerminateSignalSurvivesStashing()
    {
        FunctionRegistry.clear()
        try
        {
            runBlocking {
                val (station, _) = buildOversizedPcpStation(
                    OVERSIZED_PCP_TERMINATE_FUNCTION,
                    ::oversizedPcpTerminatingPathOutput
                )
                station.P2PInit()

                val turnResult = station.runTurn()

                assertEquals("dispatch-argument", PCP_DISPATCH_ARGUMENT.get())
                assertEquals(TurnResult.Halt(PumpStationExitReason.TerminateSignal), turnResult)
                assertStashedPcpOutputIsCompact(station, expectPassSignal = false)
            }
        }
        finally
        {
            FunctionRegistry.clear()
        }
    }
}

/** Builds a station that dispatches a serialized PCP request to the bound path function. */
private fun buildOversizedPcpStation(
    functionName: String,
    function: KFunction<*>,
    transformPathResult: Boolean = false
): Pair<PumpStation, MutableList<PumpStationEvent>>
{
    val request = PcPRequest(
        tPipeContextOptions = TPipeContextOptions().apply {
            this.functionName = functionName
        },
        argumentsOrFunctionParams = listOf("dispatch-argument")
    )
    val requestJson = serialize(request, encodedefault = true)
    val station = PumpStation()
        .setDispatchAgent(Pipeline().apply {
            add(ScriptedTestPipe(response = """{"pathName":"oversized-tool","inputData":$requestJson,"pathSchema":""}"""))
        })
        .setSkipJudgeOnFirstTurn(true)
        .setBlowoutThreshold(0.5)
        .setHistoryTransport(PumpStationHistoryTransport.ContextOnly)
    val events = mutableListOf<PumpStationEvent>()
    station.setEventObserver { events.add(it) }
    if(transformPathResult)
    {
        station.setPathTransformationFunction { content, _ ->
            val image = content.binaryContent.single() as BinaryContent.Bytes
            MultimodalContent(text = content.text).apply {
                addBinary(image.data, image.mimeType, image.filename)
                passPipeline = content.passPipeline
                terminatePipeline = content.terminatePipeline
            }
        }
    }
    station.setTokenBudgetRecursive(TokenBudgetSettings(maxTokens = 1_000, contextWindowSize = 10_000))
    val path = PathObject().apply {
        pathName = "oversized-tool"
        pathDescription = "Returns a large PCP result for overflow handling."
        pathSchema = ""
        bindFunction(functionName, function)
    }
    station.addPath(path)
    station.getTaskState().originalInput = MultimodalContent(text = "run the tool")
    return station to events
}

/** Verifies that the complete native result is stashed and only a compact reference remains visible. */
private fun assertStashedPcpOutputIsCompact(station: PumpStation, expectPassSignal: Boolean)
{
    val stash = station.getStashManifest().single()
    val stashedContent = station.retrieveStash(stash.id)
    assertNotNull(stashedContent, "The full PCP output should remain retrievable from the station stash.")
    assertEquals(OVERSIZED_PCP_OUTPUT, stashedContent.content.text)
    assertEquals(1, stashedContent.content.binaryContent.size)
    val stashedImage = stashedContent.content.binaryContent.single() as BinaryContent.Bytes
    assertContentEquals(byteArrayOf(1, 2, 3), stashedImage.data)
    assertEquals("image/png", stashedImage.mimeType)
    assertEquals("pcp-result.png", stashedImage.filename)
    assertEquals(expectPassSignal, stashedContent.content.passPipeline)
    assertEquals(!expectPassSignal, stashedContent.content.terminatePipeline)

    val latestContent = station.getTaskState().latestContent
    assertNotNull(latestContent)
    assertEquals(stash.id, latestContent.metadata["stashId"])
    assertTrue(latestContent.text.length < 500, "Latest content should contain only a compact stash reference.")
    assertFalse(latestContent.text.contains(OVERSIZED_PCP_OUTPUT))
    assertTrue(latestContent.binaryContent.isEmpty())
    assertEquals(null, latestContent.metadata["pcpOutput"])

    val lastPathResult = station.getTaskState().lastPathResult
    assertNotNull(lastPathResult)
    assertEquals(stash.id, lastPathResult.metadata["stashId"])
    assertTrue(lastPathResult.text.length < 500, "The returned path result should be a compact stash reference.")
    assertTrue(lastPathResult.binaryContent.isEmpty())
    assertEquals(expectPassSignal, lastPathResult.passPipeline)
    assertEquals(!expectPassSignal, lastPathResult.terminatePipeline)

    listOf(station.turnHistory, station.rawTurnHistory).forEach { history ->
        val historyOutput = history.history.lastOrNull { it.role == com.TTT.Context.ConverseRole.assistant }
        assertNotNull(historyOutput, "The path output should remain represented in conversation history.")
        assertEquals(stash.id, historyOutput.content.metadata["stashId"])
        assertTrue(historyOutput.content.text.length < 500, "History should contain only a compact stash reference.")
        assertFalse(historyOutput.content.text.contains(OVERSIZED_PCP_OUTPUT))
        assertTrue(historyOutput.content.binaryContent.isEmpty())
        assertEquals(null, historyOutput.content.metadata["pcpOutput"])
        assertEquals(expectPassSignal, historyOutput.content.passPipeline)
        assertEquals(!expectPassSignal, historyOutput.content.terminatePipeline)
    }
}
