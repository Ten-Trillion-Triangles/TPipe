package com.TTT.Pipe

import com.TTT.Util.buildSemanticDecompressionInstructions
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
    fun semanticDecompressionPreludeIsPrependedWhenCompressionIsEnabled()
    {
        val pipe = InspectableSemanticCompressionPipe()

        pipe.enableSemanticCompression()
        pipe.enableSemanticDecompression()
        pipe.setSystemPrompt("Base system prompt")
        pipe.applySystemPrompt()

        val prompt = pipe.currentSystemPrompt()
        val prelude = buildSemanticDecompressionInstructions()

        assertTrue(prompt.startsWith(prelude), "Decompression instructions should sit at the very top of the system prompt")
        assertTrue(prompt.contains("TPipe Semantic Compression"), "Prelude should name the compression scheme explicitly")
        assertTrue(prompt.contains("loss-minimized encoding"), "Prelude should explain that the compressed text is a reduced encoding")
        assertTrue(prompt.contains("original intent, meaning, data, and contents"), "Prelude should explain what must be restored")
        assertTrue(prompt.contains("do not assume prior knowledge"), "Prelude should warn the model not to assume familiarity")
        assertTrue(prompt.contains("Legend:"), "Prelude should explain the legend heading")
        assertTrue(prompt.contains("code: phrase"), "Prelude should explain the legend entry format")
        assertTrue(prompt.contains("first blank line"), "Prelude should explain where the legend block ends")
        assertTrue(prompt.contains("¶"), "Prelude should explain that pilcrows mark paragraph breaks")
        assertTrue(prompt.contains("sentence structure"), "Prelude should explain that sentence structure must be preserved")
        assertTrue(prompt.contains("sentence-by-sentence"), "Prelude should require sentence-by-sentence reconstruction")
        assertTrue(prompt.contains("paragraph boundaries"), "Prelude should mention paragraph preservation")
        assertTrue(prompt.contains("restore omitted articles, conjunctions, prepositions, auxiliaries, and punctuation"), "Prelude should explain how missing glue words are rebuilt")
        assertTrue(prompt.contains("reconstruct the original text as completely and faithfully as possible"), "Prelude should tell the model to fully rebuild the original text")
        assertTrue(prompt.contains("normal human English again"), "Prelude should explain the desired output style")
        assertTrue(prompt.contains("Do not leave the text compressed"), "Prelude should forbid compressed-style output")
        assertTrue(prompt.contains("Base system prompt"), "Original system prompt should still follow the prelude")
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
