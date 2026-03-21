package com.TTT.Pipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class InspectableSemanticCompressionPipe : Pipe()
{
    fun currentBudget(): TokenBudgetSettings?
    {
        return tokenBudgetSettings
    }

    fun isSemanticDecompressionEnabled(): Boolean
    {
        return semanticDecompressionEnabled
    }

    fun currentSystemPrompt(): String
    {
        return systemPrompt
    }

    override fun truncateModuleContext(): Pipe
    {
        return this
    }

    override suspend fun generateText(promptInjector: String): String
    {
        return promptInjector
    }
}

class SemanticCompressionBuilderTest
{
    @Test
    fun enableSemanticCompressionCreatesBudgetWhenMissing()
    {
        val pipe = InspectableSemanticCompressionPipe()

        pipe.enableSemanticCompression()

        val budget = pipe.currentBudget()

        assertNotNull(budget, "Compression should create token budgeting when it is missing")
        assertTrue(budget.compressUserPrompt, "Compression should be enabled on the token budget")
    }

    @Test
    fun enableSemanticCompressionPreservesExistingBudgetFields()
    {
        val pipe = InspectableSemanticCompressionPipe()

        pipe.setTokenBudget(
            TokenBudgetSettings(
                userPromptSize = 128,
                maxTokens = 2048,
                reasoningBudget = 256,
                subtractReasoningFromInput = true,
                contextWindowSize = 4096,
                allowUserPromptTruncation = true,
                preserveJsonInUserPrompt = false,
                truncateContextWindowAsString = true,
                preserveTextMatches = true
            )
        )

        pipe.enableSemanticCompression()

        val budget = pipe.currentBudget()

        assertNotNull(budget, "Budget should still exist after enabling compression")
        assertTrue(budget.compressUserPrompt, "Compression should be flipped on")
        assertEquals(128, budget.userPromptSize)
        assertEquals(2048, budget.maxTokens)
        assertEquals(256, budget.reasoningBudget)
        assertTrue(budget.subtractReasoningFromInput)
        assertEquals(4096, budget.contextWindowSize)
        assertTrue(budget.allowUserPromptTruncation)
        assertFalse(budget.preserveJsonInUserPrompt)
        assertTrue(budget.truncateContextWindowAsString)
        assertTrue(budget.preserveTextMatches)
    }

    @Test
    fun enableSemanticDecompressionSetsIndependentFlag()
    {
        val pipe = InspectableSemanticCompressionPipe()

        pipe.setSystemPrompt("Base system prompt")

        pipe.enableSemanticDecompression()
        pipe.applySystemPrompt()

        assertTrue(pipe.isSemanticDecompressionEnabled(), "Decompression should be tracked independently")
        assertEquals("Base system prompt", pipe.currentSystemPrompt(), "No decompression text should be injected yet")
        assertTrue(pipe.currentBudget() == null, "Decompression alone should not create token budgeting")
    }

    @Test
    fun semanticCompressionBuildersCanBeChained()
    {
        val pipe = InspectableSemanticCompressionPipe()

        val returnedPipe = pipe.enableSemanticCompression()
        assertTrue(returnedPipe === pipe, "Compression builder should return the same pipe instance")
        returnedPipe.enableSemanticDecompression()

        assertTrue(pipe.currentBudget()?.compressUserPrompt == true, "Compression should remain enabled")
        assertTrue(pipe.isSemanticDecompressionEnabled(), "Decompression flag should remain enabled")
    }
}
