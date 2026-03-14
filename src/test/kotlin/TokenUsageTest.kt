package com.TTT

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class TokenUsageTest {

    @Test
    fun testComprehensiveTokenUsageEnabled() = runBlocking {
        val nestedReasoning = MockTokenPipe("Reasoning-Level-2").enableComprehensiveTokenTracking()
        val primaryReasoning = MockTokenPipe("Reasoning-Level-1").enableComprehensiveTokenTracking().apply {
            setReasoningPipe(nestedReasoning)
        }
        val validatorPipe = MockTokenPipe("Validator").enableComprehensiveTokenTracking()
        val mainPipe = MockTokenPipe("Main-Pipe")
            .setReasoningPipe(primaryReasoning)
            .setValidatorPipe(validatorPipe)
            .enableComprehensiveTokenTracking()

        val pipeline = Pipeline()
            .add(mainPipe)

        val result = pipeline.execute(MultimodalContent("test input"))

        assertFalse(result.isEmpty(), "Pipeline should return a result")

        val tokenUsage = pipeline.getTokenUsage()

        assertTrue(tokenUsage.totalInputTokens > 0, "Comprehensive tracking should report input tokens")
        assertTrue(tokenUsage.totalOutputTokens > 0, "Comprehensive tracking should report output tokens")
        assertTrue(tokenUsage.childPipeTokens.isNotEmpty(), "Child pipes should be tracked")

        val mainPipeUsage = tokenUsage.childPipeTokens["pipe-0-Main-Pipe"]
        assertNotNull(mainPipeUsage, "Main pipe usage must be tracked")
        assertTrue(mainPipeUsage!!.childPipeTokens.containsKey("reasoning-Reasoning-Level-1"))
    }

    @Test
    fun testComprehensiveTokenUsageDisabled() = runBlocking {
        val mainPipe = MockTokenPipe("Main-Pipe")
            .setReasoningPipe(MockTokenPipe("Reasoning"))
            .setValidatorPipe(MockTokenPipe("Validator"))
            // Comprehensive tracking intentionally disabled

        val pipeline = Pipeline()
            .add(mainPipe)

        val result = pipeline.execute(MultimodalContent("test input"))

        val tokenUsage = mainPipe.getTokenUsage()

        assertEquals(0, tokenUsage.totalInputTokens, "No tracking should report zero input tokens")
        assertEquals(0, tokenUsage.totalOutputTokens, "No tracking should report zero output tokens")
        assertTrue(tokenUsage.childPipeTokens.isEmpty(), "No child pipes should be tracked when disabled")

        assertTrue(pipeline.inputTokensSpent > 0, "Basic pipeline input counter should still be populated")
        assertTrue(pipeline.outputTokensSpent > 0, "Basic pipeline output counter should still be populated")
    }

}
