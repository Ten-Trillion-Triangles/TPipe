package com.TTT.Util

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ConverseData
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.CoreAnalysis
import com.TTT.Structs.ExplicitReasoningDetailed
import com.TTT.Structs.LogicalStep
import com.TTT.Structs.StepByStepProcess
import com.TTT.Structs.extractReasoningStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningStreamNormalizationTest
{
    @Test
    fun explicitCotPayloadIsFlattenedIntoThoughtStream()
    {
        val payload = serialize(buildReasoningPayload("explicit round"), encodedefault = false)

        val stream = extractReasoningStream(
            method = "ExplicitCot",
            content = MultimodalContent(text = payload)
        )

        assertTrue(stream.contains("Let me think through this: explicit round"))
        assertTrue(stream.contains("Working through this step by step"))
        assertTrue(stream.contains("Based on this reasoning, the next steps should be: Use the flattened stream for the parent injection."))
        assertFalse(stream.contains("\"coreAnalysis\""))
    }

    @Test
    fun converseHistoryWrapperUnwrapsBeforeFlattening()
    {
        val innerPayload = serialize(buildReasoningPayload("nested round"), encodedefault = false)
        val outerHistory = ConverseHistory(
            mutableListOf(
                ConverseData(ConverseRole.developer, MultimodalContent(text = "ROUND 1 scaffold")),
                ConverseData(ConverseRole.system, MultimodalContent(text = "FOCUS: timing")),
                ConverseData(ConverseRole.agent, MultimodalContent(text = innerPayload))
            )
        )

        val stream = extractReasoningStream(
            method = "ExplicitCot",
            content = MultimodalContent(text = serialize(outerHistory, encodedefault = false))
        )

        assertTrue(stream.contains("Let me think through this: nested round"))
        assertTrue(stream.contains("The key components I need to consider are: timing, history transport"))
        assertFalse(stream.contains("\"history\""))
        assertFalse(stream.contains("ConverseHistory"))
    }

    @Test
    fun alreadyFlattenedTextPassesThroughUnchanged()
    {
        val plainText = """
            ROUND 2
            FOCUS: count exact objects

            Let me think through this: already flattened.
        """.trimIndent()

        val stream = extractReasoningStream(
            method = "ExplicitCot",
            content = MultimodalContent(text = plainText)
        )

        assertEquals(plainText, stream)
    }

    private fun buildReasoningPayload(subject: String): ExplicitReasoningDetailed
    {
        return ExplicitReasoningDetailed(
            coreAnalysis = CoreAnalysis(
                analysisSubject = subject,
                identifiedComponents = listOf("timing", "history transport"),
                underlyingIssues = listOf("flattening", "round separation"),
                knownFacts = listOf("single round uses unravel()")
            ),
            logicalBreakdown = listOf(
                "The first step is to normalize the round output.",
                "The second step is to append the flattened block."
            ),
            sequentialReasoning = StepByStepProcess(
                totalSteps = 2,
                steps = listOf(
                    LogicalStep(
                        stepId = 1,
                        reasoningStep = "Inspect the raw round output",
                        contextualFocus = "history wrapper",
                        considerations = "preserve separation",
                        deductionProcess = "unwrap to the visible stream",
                        conclusion = "the next stage sees plain text",
                        reasoningExplanation = "This keeps the round readable",
                        connectionToNext = "Proceed to the final stream"
                    ),
                    LogicalStep(
                        stepId = 2,
                        reasoningStep = "Carry the stream forward",
                        contextualFocus = "parent injection",
                        considerations = "plain text only",
                        deductionProcess = "append the block",
                        conclusion = "the main pipe receives thought text",
                        reasoningExplanation = "The history remains internal",
                        connectionToNext = ""
                    )
                ),
                dependencies = listOf("round normalization"),
                verificationPoints = listOf("no converse JSON leaks")
            ),
            recommendedSteps = "Use the flattened stream for the parent injection."
        )
    }
}
