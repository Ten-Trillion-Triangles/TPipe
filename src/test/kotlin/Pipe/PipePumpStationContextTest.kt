package com.TTT.Pipe

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.PumpStation
import com.TTT.testing.TestCapturingPipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipePumpStationContextTest
{
    private fun executeInStation(pipe: TestCapturingPipe, station: PumpStation = PumpStation())
    {
        val pipeline = Pipeline().add(pipe)
        pipeline.setParentInterface(station)
        runBlocking {
            pipeline.execute(MultimodalContent(text = "request"))
        }
    }

    @Test
    fun pumpStationContextIsNotImportedByDefault()
    {
        val station = PumpStation()
        station.contextWindow.contextElements.add("station-context")
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")

        executeInStation(pipe, station)

        assertFalse(pipe.getContextWindowObject().contextElements.contains("station-context"))
    }

    @Test
    fun pullPumpStationContextImportsContextWindow()
    {
        val station = PumpStation()
        station.contextWindow.contextElements.add("station-context")
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")
        pipe.pullPumpStationContext()
        pipe.autoInjectContext("Use supplied context.")

        executeInStation(pipe, station)

        assertTrue(pipe.getContextWindowObject().contextElements.contains("station-context"))
    }

    @Test
    fun pullPumpStationContextImportsMiniBankPages()
    {
        val station = PumpStation()
        station.miniBank.contextMap["station-page"] = ContextWindow().apply {
            contextElements.add("station-page-context")
        }
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")
        pipe.pullPumpStationContext()

        executeInStation(pipe, station)

        assertEquals(
            listOf("station-page-context"),
            pipe.getMiniContextBankObject().contextMap["station-page"]?.contextElements
        )
    }

    @Test
    fun pumpStationContextMergesAfterParentPipeContext()
    {
        val station = PumpStation()
        station.contextWindow.loreBookKeys["shared"] = LoreBook().apply {
            key = "shared"
            value = "station"
        }
        val pipeline = Pipeline()
        pipeline.context.loreBookKeys["shared"] = LoreBook().apply {
            key = "shared"
            value = "parent"
        }
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")
        pipe.pullPipelineContext()
        pipe.pullPumpStationContext()
        pipeline.add(pipe)
        pipeline.setParentInterface(station)

        runBlocking {
            pipeline.execute(MultimodalContent(text = "request"))
        }

        assertEquals("station", pipe.getContextWindowObject().loreBookKeys["shared"]?.value)
    }

    @Test
    fun pullPumpStationContextWithoutPumpStationSafelyNoOps()
    {
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")
        pipe.pullPumpStationContext()

        runBlocking {
            pipe.execute(MultimodalContent(text = "request"))
        }

        assertTrue(pipe.getContextWindowObject().isEmpty())
        assertTrue(pipe.getMiniContextBankObject().isEmpty())
    }

    @Test
    fun importedContextDoesNotAliasPumpStationState()
    {
        val station = PumpStation()
        station.contextWindow.contextElements.add("station-context")
        station.miniBank.contextMap["station-page"] = ContextWindow().apply {
            contextElements.add("station-page-context")
        }
        val pipe = TestCapturingPipe(response = "response")
        pipe.setUserPrompt("prompt")
        pipe.pullPumpStationContext()

        executeInStation(pipe, station)
        pipe.getContextWindowObject().contextElements.add("pipe-only")
        pipe.getMiniContextBankObject().contextMap["station-page"]?.contextElements?.add("pipe-page-only")

        assertEquals(listOf("station-context"), station.contextWindow.contextElements)
        assertEquals(
            listOf("station-page-context"),
            station.miniBank.contextMap["station-page"]?.contextElements
        )
    }
}
