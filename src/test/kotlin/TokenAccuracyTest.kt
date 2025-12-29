package com.TTT

import com.TTT.Context.Dictionary
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenAccuracyTest {

    @Test
    fun testTokenCountingAccuracyWhenEnabled() = runBlocking {
        val pipe = TestTokenPipe("Test-Pipe")
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
        assertTrue(usage.inputTokens < originalTokens, "Tracked input tokens should reflect truncated content")
    }

    @Test
    fun testTokenCountingWhenDisabled() = runBlocking {
        val pipe = TestTokenPipe("Test-Pipe")
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
