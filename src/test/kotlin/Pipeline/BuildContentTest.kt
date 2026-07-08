package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildContentTest
{
    @Test
    fun testBuildTurnContentIncludesHistoryAndContext()
    {
        val station = PumpStation()
        val content = station.buildTurnContent()
        assertNotNull(content.context)
        assertEquals(station.turnHistory, content.context.converseHistory)
        assertEquals(station.contextWindow.loreBookKeys, content.context.loreBookKeys)
    }

    @Test
    fun testBuildGoalContentIncludesRawTurnHistory()
    {
        val station = PumpStation()
        val content = station.buildGoalContent()
        assertTrue(content.metadata.containsKey("taskState"))
    }

    private val researchTopic = "Research the following topic: Kotlin coroutines vs Java virtual threads"

    @Test
    fun testBuildUserMessageForTurn_embedsOriginalInputOnTurnZero()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = researchTopic)
        station.taskState.phase = PumpStationPhase.Dispatch

        assertTrue(
            station.buildUserMessageForTurn().contains(researchTopic),
            "buildUserMessageForTurn() must embed taskState.originalInput.text — " +
                "the dispatch LLM cannot route a task it has never seen."
        )
    }

    @Test
    fun testBuildUserMessageForTurn_keepsOriginalInputAlongsideSummary()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = researchTopic)
        station.taskState.phase = PumpStationPhase.Dispatch
        station.turnSummary = "Prior summary must not erase the original task."

        assertTrue(
            station.buildUserMessageForTurn().contains(researchTopic),
            "buildUserMessageForTurn() must keep originalInput.text visible alongside turnSummary."
        )
    }

    @Test
    fun testBuildPathInput_prefersOriginalInputOverEmptyPathSchema()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = researchTopic)

        val input = station.buildPathInput(
            path = PathObject(),
            request = PathRequest(pathName = "report", pathSchema = "", pathSelectionRationale = null)
        )

        assertTrue(
            input.text.contains(researchTopic),
            "buildPathInput() must surface originalInput.text when dispatch leaves pathSchema empty."
        )
    }

    @Test
    fun testBuildPathInput_mergesOriginalInputEvenWhenDispatchEmitsPathSchema()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = researchTopic)

        val input = station.buildPathInput(
            path = PathObject(),
            request = PathRequest(pathName = "report", pathSchema = "inputData: { topic: string }", pathSelectionRationale = null)
        )

        assertTrue(
            input.text.contains(researchTopic),
            "buildPathInput() must merge originalInput.text with dispatch pathSchema — " +
                "never replace the original task with dispatch garbage."
        )
    }
}