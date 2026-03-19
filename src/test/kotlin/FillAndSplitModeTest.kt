package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Context.Dictionary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FillAndSplitTestPipe : Pipe()
{
    override fun truncateModuleContext(): Pipe = this
    override suspend fun generateText(promptInjector: String): String = "test"
}

class FillAndSplitModeTest
{
    @Test
    fun fillAndSplitModeIsExposedThroughPipeSettings()
    {
        val pipe = FillAndSplitTestPipe()
            .autoTruncateContext(fillAndSplitMode = true)

        val truncationSettings = pipe.getTruncationSettings()

        assertTrue(truncationSettings.fillMode)
        assertTrue(truncationSettings.fillAndSplitMode)
    }

    @Test
    fun fillAndSplitKeepsLorebookAndContextWithinSeparateHalves()
    {
        val fillOnlyWindow = buildWindow()
        val fillAndSplitWindow = buildWindow()

        val prompt = "alpha beta"
        val budget = 26

        fillOnlyWindow.selectAndTruncateContext(
            text = prompt,
            totalTokenBudget = budget,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            fillMode = true
        )

        fillAndSplitWindow.selectAndTruncateContext(
            text = prompt,
            totalTokenBudget = budget,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            fillMode = true,
            fillAndSplitMode = true
        )

        assertEquals(2, fillOnlyWindow.loreBookKeys.size)
        assertTrue(fillOnlyWindow.loreBookKeys.containsKey("alpha"))
        assertTrue(fillOnlyWindow.loreBookKeys.containsKey("beta"))

        assertEquals(1, fillAndSplitWindow.loreBookKeys.size)
        assertTrue(fillAndSplitWindow.loreBookKeys.containsKey("alpha"))
        assertFalse(fillAndSplitWindow.loreBookKeys.containsKey("beta"))

        assertTrue(totalTokens(fillOnlyWindow) <= budget)
        assertTrue(totalTokens(fillAndSplitWindow) <= budget)
    }

    @Test
    fun fillAndSplitModeReclaimsUnusedLorebookBudget()
    {
        val fillAndSplitWindow = buildUnderfilledWindow()
        val halfBudgetBaseline = buildUnderfilledWindow()
        val prompt = "alpha"
        val budget = 20

        halfBudgetBaseline.loreBookKeys = halfBudgetBaseline.loreBookKeys.filterKeys {
            it in halfBudgetBaseline.selectAndFillLoreBookContext(
                text = prompt,
                maxTokens = budget / 2,
                countSubWordsInFirstWord = true,
                favorWholeWords = false,
                countOnlyFirstWordFound = false,
                splitForNonWordChar = true,
                alwaysSplitIfWholeWordExists = false,
                countSubWordsIfSplit = false,
                nonWordSplitCount = 2
            )
        }.toMutableMap()

        halfBudgetBaseline.truncateContextElements(
            maxTokens = budget / 2,
            multiplyWindowSizeBy = 1,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            countSubWordsInFirstWord = true,
            favorWholeWords = false,
            countOnlyFirstWordFound = false,
            splitForNonWordChar = true,
            alwaysSplitIfWholeWordExists = false,
            countSubWordsIfSplit = false,
            nonWordSplitCount = 2
        )

        fillAndSplitWindow.selectAndTruncateContext(
            text = prompt,
            totalTokenBudget = budget,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            fillMode = true,
            fillAndSplitMode = true
        )

        val fillAndSplitTokens = totalTokens(fillAndSplitWindow)
        val baselineTokens = totalTokens(halfBudgetBaseline)

        assertTrue(
            fillAndSplitTokens > baselineTokens,
            "Expected fillAndSplit to retain more tokens than the half-budget baseline, but got $fillAndSplitTokens <= $baselineTokens"
        )
    }

    @Test
    fun fillAndSplitModeReclaimsUnusedLorebookBudgetSuspend()
    {
        runBlocking {
            val fillAndSplitWindow = buildUnderfilledWindow()
            val halfBudgetBaseline = buildUnderfilledWindow()
            val prompt = "alpha"
            val budget = 20

            halfBudgetBaseline.loreBookKeys = halfBudgetBaseline.loreBookKeys.filterKeys {
                it in halfBudgetBaseline.selectAndFillLoreBookContextSuspend(
                    text = prompt,
                    maxTokens = budget / 2,
                    countSubWordsInFirstWord = true,
                    favorWholeWords = false,
                    countOnlyFirstWordFound = false,
                    splitForNonWordChar = true,
                    alwaysSplitIfWholeWordExists = false,
                    countSubWordsIfSplit = false,
                    nonWordSplitCount = 2
                )
            }.toMutableMap()

            halfBudgetBaseline.truncateContextElements(
                maxTokens = budget / 2,
                multiplyWindowSizeBy = 1,
                truncateSettings = ContextWindowSettings.TruncateBottom,
                countSubWordsInFirstWord = true,
                favorWholeWords = false,
                countOnlyFirstWordFound = false,
                splitForNonWordChar = true,
                alwaysSplitIfWholeWordExists = false,
                countSubWordsIfSplit = false,
                nonWordSplitCount = 2
            )

            fillAndSplitWindow.selectAndTruncateContextSuspend(
                text = prompt,
                totalTokenBudget = budget,
                multiplyWindowSizeBy = 0,
                truncateSettings = ContextWindowSettings.TruncateBottom,
                fillMode = true,
                fillAndSplitMode = true
            )

            val fillAndSplitTokens = totalTokens(fillAndSplitWindow)
            val baselineTokens = totalTokens(halfBudgetBaseline)

            assertTrue(
                fillAndSplitTokens > baselineTokens,
                "Expected fillAndSplit to retain more tokens than the half-budget baseline, but got $fillAndSplitTokens <= $baselineTokens"
            )
        }
    }

    private fun buildWindow(): ContextWindow
    {
        val window = ContextWindow()

        window.addLoreBookEntry(
            "alpha",
            "alpha lorebook entry keeps a stable chunk of context tokens",
            10
        )
        window.addLoreBookEntry(
            "beta",
            "beta lorebook entry keeps a stable chunk of context tokens",
            1
        )

        window.contextElements.addAll(
            listOf(
                "context element one keeps the runtime state visible",
                "context element two keeps the runtime state visible"
            )
        )

        window.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("prior user turn keeps the conversation grounded")
        )
        window.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("prior assistant turn keeps the conversation grounded")
        )

        return window
    }

    private fun buildUnderfilledWindow(): ContextWindow
    {
        val window = ContextWindow()

        window.addLoreBookEntry(
            "alpha",
            "alpha",
            10
        )

        window.contextElements.addAll(
            List(20) { "context-$it" }
        )

        return window
    }

    private fun totalTokens(window: ContextWindow): Int
    {
        val lorebookTokens = window.loreBookKeys.values.sumOf { Dictionary.countTokens(it.value) }
        val contextTokens = window.contextElements.sumOf { Dictionary.countTokens(it) }
        val historyTokens = window.converseHistory.history.sumOf { Dictionary.countTokens(it.content.text) }
        return lorebookTokens + contextTokens + historyTokens
    }
}
