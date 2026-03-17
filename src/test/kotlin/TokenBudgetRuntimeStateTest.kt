package com.TTT

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pipe subclass that exposes token-budget state so runtime restore behavior can be asserted in tests.
 */
private class InspectableBudgetPipe(private val displayName: String) : Pipe()
{
    init {
        pipeName = displayName
    }

    /**
     * Adds a context element so token budgeting has mutable context to truncate.
     *
     * @param value Context string to append to the pipe's working [contextWindow].
     */
    fun addContextElement(value: String)
    {
        contextWindow.contextElements.add(value)
    }

    /**
     * Reads the currently configured max-token value from the pipe.
     *
     * @return The current `maxTokens` field value.
     */
    fun readMaxTokens(): Int
    {
        return maxTokens
    }

    /**
     * Reads the currently configured context-window value from the pipe.
     *
     * @return The current `contextWindowSize` field value.
     */
    fun readContextWindowSize(): Int
    {
        return contextWindowSize
    }

    /**
     * Reads a detached copy of the current token-budget settings.
     *
     * @return Copy of the current token-budget configuration, or null when budgeting is disabled.
     */
    fun readTokenBudgetSettings(): TokenBudgetSettings?
    {
        return tokenBudgetSettings?.copy(
            pageWeights = tokenBudgetSettings?.pageWeights?.toMap()
        )
    }

    /**
     * Test helper that does not apply provider-specific truncation.
     *
     * @return This pipe instance.
     */
    override fun truncateModuleContext(): Pipe
    {
        return this
    }

    /**
     * Generates deterministic text output for budget-state tests.
     *
     * @param promptInjector Prompt content forwarded by the base pipe.
     * @return Deterministic test output.
     */
    override suspend fun generateText(promptInjector: String): String
    {
        return "$displayName generated text: $promptInjector"
    }

    /**
     * Generates deterministic multimodal output for budget-state tests.
     *
     * @param content Input content forwarded by the base pipe.
     * @return Content with a deterministic suffix so repeated executions are easy to trace.
     */
    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        return MultimodalContent(text = "${content.text} -> handled by $displayName")
    }
}

/**
 * Regression tests for token-budget runtime state restoration.
 */
class TokenBudgetRuntimeStateTest
{
    /**
     * Verifies that repeated executions against the same pipe do not leave behind mutated budget fields.
     */
    @Test
    fun tokenBudgetRuntimeRestoresPipeStateBetweenExecutions() = runBlocking {
        val pipe = InspectableBudgetPipe("budget-runtime")
        pipe.addContextElement("dragon lore that is long enough to exercise context truncation and budget calculations.")
        pipe.setContextWindowSize(256)
        pipe.setMaxTokens(96)
        pipe.setTokenBudget(
            TokenBudgetSettings(
                contextWindowSize = 256,
                maxTokens = 96,
                reasoningBudget = 24,
                allowUserPromptTruncation = true
            )
        )

        val expectedMaxTokens = pipe.readMaxTokens()
        val expectedContextWindowSize = pipe.readContextWindowSize()
        val expectedBudgetSettings = pipe.readTokenBudgetSettings()

        pipe.execute(MultimodalContent(text = "first execution prompt with enough text to trigger dynamic budgeting"))
        assertEquals(expectedMaxTokens, pipe.readMaxTokens())
        assertEquals(expectedContextWindowSize, pipe.readContextWindowSize())
        assertEquals(expectedBudgetSettings, pipe.readTokenBudgetSettings())

        pipe.execute(MultimodalContent(text = "second execution prompt with enough text to repeat the same budgeting path"))
        assertEquals(expectedMaxTokens, pipe.readMaxTokens())
        assertEquals(expectedContextWindowSize, pipe.readContextWindowSize())
        assertEquals(expectedBudgetSettings, pipe.readTokenBudgetSettings())
    }

    /**
     * Verifies that pipeline-level reuse of the same pipe instance also restores token-budget state cleanly.
     */
    @Test
    fun tokenBudgetRuntimeRestoresPipeStateAcrossPipelineExecutions() = runBlocking {
        val sharedPipe = InspectableBudgetPipe("pipeline-budget")
        sharedPipe.addContextElement("pipeline context that should be truncated without mutating later runs.")
        sharedPipe.setContextWindowSize(192)
        sharedPipe.setMaxTokens(72)
        sharedPipe.setTokenBudget(
            TokenBudgetSettings(
                contextWindowSize = 192,
                maxTokens = 72,
                reasoningBudget = 16,
                allowUserPromptTruncation = true
            )
        )

        val expectedMaxTokens = sharedPipe.readMaxTokens()
        val expectedContextWindowSize = sharedPipe.readContextWindowSize()
        val expectedBudgetSettings = sharedPipe.readTokenBudgetSettings()

        val pipeline = Pipeline()
            .add(sharedPipe)

        pipeline.execute(MultimodalContent(text = "pipeline execution one with enough prompt data to activate budgeting"))
        assertEquals(expectedMaxTokens, sharedPipe.readMaxTokens())
        assertEquals(expectedContextWindowSize, sharedPipe.readContextWindowSize())
        assertEquals(expectedBudgetSettings, sharedPipe.readTokenBudgetSettings())

        pipeline.execute(MultimodalContent(text = "pipeline execution two with enough prompt data to activate budgeting again"))
        assertEquals(expectedMaxTokens, sharedPipe.readMaxTokens())
        assertEquals(expectedContextWindowSize, sharedPipe.readContextWindowSize())
        assertEquals(expectedBudgetSettings, sharedPipe.readTokenBudgetSettings())
    }

    @Test
    fun testSubtractReasoningFromInputTrue()
    {
        val pipe = InspectableBudgetPipe("Test")
            .setSystemPrompt("test")
            .setPromptMode(com.TTT.Enums.PromptMode.singlePrompt)
            .setMaxTokens(128)
            .setContextWindowSize(200)
            .setTokenBudget(
                TokenBudgetSettings(
                    maxTokens = 128,
                    reasoningBudget = 32,
                    subtractReasoningFromInput = true,
                    contextWindowSize = 200
                )
            ) as InspectableBudgetPipe

        assertEquals(128, pipe.readMaxTokens(), "Reasoning tokens should not be subtracted from maxTokens when subtractReasoningFromInput is true")
        assertEquals(39, pipe.readContextWindowSize(), "Reasoning tokens should be subtracted from the input window when subtractReasoningFromInput is true")
    }

    @Test
    fun testSubtractReasoningFromInputExceedsWindow()
    {
        val exception = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            InspectableBudgetPipe("Test")
                .setSystemPrompt("test")
                .setMaxTokens(128)
                .setContextWindowSize(200)
                .setTokenBudget(
                    TokenBudgetSettings(
                        maxTokens = 128,
                        reasoningBudget = 250,
                        subtractReasoningFromInput = true,
                        contextWindowSize = 200
                    )
                )
        }
        kotlin.test.assertTrue(exception.message!!.contains("Reasoning tokens cannot be greater than the input token budget"))
    }
}
