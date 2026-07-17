package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for Defect 15: turnSummary text is concatenated into the
 * user message without demarcation, so the judge LLM can mistake the summary
 * for the question being asked.
 *
 * Contract: when turnSummary is non-blank, the user message text wraps it in
 * [TURN SUMMARY] ... [/TURN SUMMARY] markers. This mirrors the
 * [CONVERSATION HISTORY] ... [/CONVERSATION HISTORY] demarcation in
 * buildUserMessageForTurn().
 */
class PumpStationTurnSummaryDemarcationTest
{
    @Test
    fun turnSummaryIsWrappedInDemarcationMarkers()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = "user question")
        station.turnSummary = "summary of the prior turn"

        val userMessage = station.buildUserMessageForTurn()

        assertTrue(
            userMessage.contains("[TURN SUMMARY]"),
            "Defect 15: buildUserMessageForTurn must wrap turnSummary in [TURN SUMMARY] markers. Got: $userMessage"
        )
        assertTrue(
            userMessage.contains("[/TURN SUMMARY]"),
            "Defect 15: buildUserMessageForTurn must close the turnSummary block with [/TURN SUMMARY]. Got: $userMessage"
        )
        assertTrue(
            userMessage.contains("summary of the prior turn"),
            "Defect 15: turnSummary text must still be present in the message. Got: $userMessage"
        )
    }

    @Test
    fun emptyTurnSummarySkipsDemarcationBlock()
    {
        val station = PumpStation()
        station.taskState.originalInput = MultimodalContent(text = "user question")
        station.turnSummary = ""

        val userMessage = station.buildUserMessageForTurn()

        assertFalse(
            userMessage.contains("[TURN SUMMARY]"),
            "When turnSummary is blank, no [TURN SUMMARY] block should be appended. Got: $userMessage"
        )
    }
}
