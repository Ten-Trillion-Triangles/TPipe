package com.TTT.Pipe

import kotlin.test.*

/**
 * Pins the cast-safety contract for [Pipe.getMiddlePromptForReasoning] and
 * [Pipe.getFooterPromptForReasoning]: a reasoning pipe whose pipeMetadata omits the
 * injectMiddlePrompt / injectFooterPrompt keys MUST yield an empty string instead
 * of throwing NullPointerException.
 *
 * Defense-in-depth layer of the two-layer fix. Even if a future reasoning-pipe builder
 * bypasses [Defaults.reasoning.ReasoningBuilder.assignDefaults], the feature does not
 * crash — it silently disables middle/footer prompt injection, which matches the
 * documented default in `ReasoningSettings.injectMiddlePrompt = false`.
 *
 * Bug history: Pipe.kt:8033 and Pipe.kt:8047 used unguarded `as Boolean` casts against
 * `pipeMetadata["injectMiddlePrompt"]` and `pipeMetadata["injectFooterPrompt"]`. The
 * Mantle reasoning-pipe builders (`BedrockConfig.buildMantleAuthorPipe`,
 * `BedrockConfig.buildMantleReasoningPipe`) bypass assignDefaults and shipped without
 * those keys, causing NullPointerException → retry absorption → degraded reasoning output
 * (empty `{}` JSON from Play Detection Agent, permissive `isValid: true` from
 * `mantle validator pipe`). See BUG_INJECTMIDDLEPROMPT_GEMMA.md for the full trace.
 *
 * Provider-agnostic parity (the structural layer of the two-layer fix) is covered by
 * `Defaults.reasoning.ReasoningBuilderParityTest` in TPipe-Defaults. This file covers
 * the cast-safety layer in the base module where the unguarded cast lived.
 */
class PipePromptInjectionReasoningTest
{
    private class TestPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String
        {
            return ""
        }

        override fun truncateModuleContext(): Pipe
        {
            return this
        }
    }

    private fun newParent(): TestPipe = TestPipe().setPipeName("parent") as TestPipe

    private fun newReasoningPipe(): TestPipe = TestPipe().setPipeName("reasoning") as TestPipe

    //=================================== getMiddlePromptForReasoning ===================================

    @Test
    fun `middle prompt returns empty string when reasoning pipe is null`()
    {
        val parent = newParent()
        assertEquals("", parent.getMiddlePromptForReasoning())
    }

    @Test
    fun `middle prompt returns empty string when injectMiddlePrompt key is absent from pipeMetadata`()
    {
        val parent = newParent()
        parent.setReasoningPipe(newReasoningPipe())
        // pipeMetadata is the empty map declared at Pipe.kt:1767 — no injectMiddlePrompt key.
        // Pre-fix this throws NullPointerException at Pipe.kt:8033.
        assertEquals("", parent.getMiddlePromptForReasoning())
    }

    @Test
    fun `middle prompt returns instructions when injectMiddlePrompt is true`()
    {
        val parent = newParent()
        parent.setMiddlePrompt("##MIDDLE##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectMiddlePrompt"] = true
        parent.setReasoningPipe(reasoning)
        assertEquals("##MIDDLE##", parent.getMiddlePromptForReasoning())
    }

    @Test
    fun `middle prompt returns empty string when injectMiddlePrompt is false`()
    {
        val parent = newParent()
        parent.setMiddlePrompt("##MIDDLE##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectMiddlePrompt"] = false
        parent.setReasoningPipe(reasoning)
        assertEquals("", parent.getMiddlePromptForReasoning())
    }

    @Test
    fun `middle prompt returns empty string when injectMiddlePrompt is wrong type`()
    {
        val parent = newParent()
        parent.setMiddlePrompt("##MIDDLE##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectMiddlePrompt"] = "true"
        parent.setReasoningPipe(reasoning)
        assertEquals("", parent.getMiddlePromptForReasoning())
    }

    //=================================== getFooterPromptForReasoning ====================================

    @Test
    fun `footer prompt returns empty string when reasoning pipe is null`()
    {
        val parent = newParent()
        assertEquals("", parent.getFooterPromptForReasoning())
    }

    @Test
    fun `footer prompt returns empty string when injectFooterPrompt key is absent from pipeMetadata`()
    {
        val parent = newParent()
        parent.setReasoningPipe(newReasoningPipe())
        // Pre-fix this throws NullPointerException at Pipe.kt:8047.
        assertEquals("", parent.getFooterPromptForReasoning())
    }

    @Test
    fun `footer prompt returns instructions when injectFooterPrompt is true`()
    {
        val parent = newParent()
        parent.setFooterPrompt("##FOOTER##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectFooterPrompt"] = true
        parent.setReasoningPipe(reasoning)
        assertEquals("##FOOTER##", parent.getFooterPromptForReasoning())
    }

    @Test
    fun `footer prompt returns empty string when injectFooterPrompt is false`()
    {
        val parent = newParent()
        parent.setFooterPrompt("##FOOTER##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectFooterPrompt"] = false
        parent.setReasoningPipe(reasoning)
        assertEquals("", parent.getFooterPromptForReasoning())
    }

    @Test
    fun `footer prompt returns empty string when injectFooterPrompt is wrong type`()
    {
        val parent = newParent()
        parent.setFooterPrompt("##FOOTER##")
        val reasoning = newReasoningPipe()
        reasoning.pipeMetadata["injectFooterPrompt"] = "true"
        parent.setReasoningPipe(reasoning)
        assertEquals("", parent.getFooterPromptForReasoning())
    }
}
