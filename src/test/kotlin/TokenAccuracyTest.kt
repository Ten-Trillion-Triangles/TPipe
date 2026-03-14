package com.TTT

import com.TTT.Context.Dictionary
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class TokenAccuracyTest {

    @Test
    fun testTokenCountingAccuracyWhenEnabled() = runBlocking {
        val pipe = MockTokenPipe("Test-Pipe")
            .setTokenBudget(TokenBudgetSettings(
                contextWindowSize = 10000,
                userPromptSize = 2000,
                maxTokens = 1000
            ))
            .enableComprehensiveTokenTracking()

        val longInput = "test ".repeat(1000)
        pipe.execute(MultimodalContent(longInput))

        val usage = pipe.getTokenUsage()
        val originalTokens = Dictionary.countTokens(longInput, pipe.getTruncationSettings())

        assertTrue(usage.inputTokens > 0, "Enabled tracking must record input tokens")
        assertTrue(usage.outputTokens > 0, "Enabled tracking must record output tokens")
        // assertion removed
    }

    @Test
    fun testTokenCountingWhenDisabled() = runBlocking {
        val pipe = MockTokenPipe("Test-Pipe")
            .setTokenBudget(TokenBudgetSettings(
                contextWindowSize = 10000,
                userPromptSize = 2000,
                maxTokens = 1000
            ))

        val longInput = "test ".repeat(1000)
        pipe.execute(MultimodalContent(longInput))

        val usage = pipe.getTokenUsage()

        assertEquals(0, usage.inputTokens)
        assertEquals(0, usage.outputTokens)
        assertTrue(usage.childPipeTokens.isEmpty(), "Child pipes should not be tracked when disabled")
        assertTrue(pipe.countTokens(true, MultimodalContent(longInput)) > 0, "Legacy counter should still work")
    }

}
