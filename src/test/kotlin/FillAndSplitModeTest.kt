package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseRole
import com.TTT.Context.buildLorebookScanText
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

    @Test
    fun fillAndSplitModeReclaimsUnusedLorebookBudgetForHistoryOnlyWindow()
    {
        val fillAndSplitWindow = buildUnderfilledHistoryWindow()
        val halfBudgetBaseline = buildUnderfilledHistoryWindow()
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

        halfBudgetBaseline.truncateConverseHistory(
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
            "Expected fillAndSplit to retain more tokens than the half-budget baseline for history-only windows, but got $fillAndSplitTokens <= $baselineTokens"
        )
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

    private fun buildUnderfilledHistoryWindow(): ContextWindow
    {
        val window = ContextWindow()

        window.addLoreBookEntry(
            "alpha",
            "alpha",
            10
        )

        window.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("history entry one keeps the conversation grounded")
        )
        window.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("history entry two keeps the conversation grounded")
        )
        window.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("history entry three keeps the conversation grounded")
        )
        window.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("history entry four keeps the conversation grounded")
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

    @Test
    fun useEntireContextForLoreSelectionOff_excludesContextElementMatches()
    {
        val window = ContextWindow()
        window.addLoreBookEntry(
            "dragonlord",
            "dragonlord lorebook entry keeps the runtime state visible",
            10
        )
        window.addLoreBookEntry(
            "decoy",
            "decoy lorebook entry keeps the runtime state visible",
            10
        )

        window.contextElements.add("the dragonlord arrived at dawn")

        val userPrompt = "Tell me about the hero."
        window.selectAndTruncateContext(
            text = userPrompt,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom
        )

        assertTrue(
            !window.loreBookKeys.containsKey("dragonlord"),
            "Without the flag, lorebook keys that only match contextElements must not be selected"
        )
        assertTrue(
            !window.loreBookKeys.containsKey("decoy"),
            "Decoy lorebook keys with no prompt match must not be selected"
        )
    }

    @Test
    fun useEntireContextForLoreSelectionOn_includesContextElementMatches()
    {
        val window = ContextWindow()
        window.addLoreBookEntry(
            "dragonlord",
            "dragonlord lorebook entry keeps the runtime state visible",
            10
        )
        window.addLoreBookEntry(
            "decoy",
            "decoy lorebook entry keeps the runtime state visible",
            10
        )

        window.contextElements.add("the dragonlord arrived at dawn")

        val userPrompt = "Tell me about the hero."
        val scanText = window.buildLorebookScanText(userPrompt, useEntireContext = true)
        window.selectAndTruncateContext(
            text = scanText,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom
        )

        assertTrue(
            window.loreBookKeys.containsKey("dragonlord"),
            "With the flag ON, lorebook keys that match contextElements must be selected"
        )
        assertTrue(
            !window.loreBookKeys.containsKey("decoy"),
            "Lorebook keys with no match anywhere in the scan text must still be filtered out"
        )
    }

    @Test
    fun useEntireContextForLoreSelectionOn_includesConverseHistoryMatches()
    {
        val window = ContextWindow()
        window.addLoreBookEntry(
            "phoenix",
            "phoenix lorebook entry keeps the runtime state visible",
            10
        )
        window.addLoreBookEntry(
            "decoy",
            "decoy lorebook entry keeps the runtime state visible",
            10
        )

        window.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("the phoenix rose from the ashes")
        )
        window.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("assistant turn keeps the conversation grounded")
        )

        val userPrompt = "Tell me about the hero."
        val scanText = window.buildLorebookScanText(userPrompt, useEntireContext = true)
        window.selectAndTruncateContext(
            text = scanText,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom
        )

        assertTrue(
            window.loreBookKeys.containsKey("phoenix"),
            "With the flag ON, lorebook keys that match converseHistory must be selected"
        )
        assertTrue(
            !window.loreBookKeys.containsKey("decoy"),
            "Lorebook keys with no match anywhere in the scan text must still be filtered out"
        )
    }

    @Test
    fun useEntireContextForLoreSelection_defaultIsOff()
    {
        // The flag lives on Pipe, not on ContextWindow. The contract is:
        // any caller that has not opted in (useEntireContext = false) must
        // see zero behavior change. The helper is the only seam we need to
        // exercise from the ContextWindow side, since the default flows
        // through the unscanned user prompt.
        val window = ContextWindow()
        window.addLoreBookEntry(
            "dragonlord",
            "dragonlord lorebook entry keeps the runtime state visible",
            10
        )
        window.contextElements.add("the dragonlord arrived at dawn")

        val userPrompt = "Tell me about the hero."
        val scanText = window.buildLorebookScanText(userPrompt, useEntireContext = false)
        assertEquals(
            userPrompt,
            scanText,
            "The default-off contract must keep the helper returning the user prompt verbatim"
        )

        window.selectAndTruncateContext(
            text = scanText,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom
        )

        assertFalse(
            window.loreBookKeys.containsKey("dragonlord"),
            "Default-off callers must not get contextElements-expanded lorebook matches"
        )
    }

    @Test
    fun useEntireContextForLoreSelection_interactsWithFillMode()
    {
        // Both the priority step and the fill step respect the new scan text.
        // The flag controls ONLY what text is fed to the matcher; fill mode's
        // behavior of adding remaining entries by weight is unchanged.

        val offWindow = ContextWindow()
        offWindow.addLoreBookEntry(
            "alpha",
            "alpha lorebook entry keeps the runtime state visible",
            10
        )
        offWindow.addLoreBookEntry(
            "beta",
            "beta lorebook entry keeps the runtime state visible",
            10
        )
        offWindow.contextElements.add("beta keeps the runtime state visible")

        val offScanText = offWindow.buildLorebookScanText("Tell me about alpha.", useEntireContext = false)
        offWindow.selectAndTruncateContext(
            text = offScanText,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            fillMode = true
        )

        assertTrue(
            offWindow.loreBookKeys.containsKey("alpha"),
            "Off + fillMode: the prompt-matched lorebook key must survive priority selection"
        )
        assertTrue(
            offWindow.loreBookKeys.containsKey("beta"),
            "Off + fillMode: the fill step adds remaining entries by weight, so beta (matching the scan text via fill weight sort) still survives"
        )

        val onWindow = ContextWindow()
        onWindow.addLoreBookEntry(
            "alpha",
            "alpha lorebook entry keeps the runtime state visible",
            10
        )
        onWindow.addLoreBookEntry(
            "beta",
            "beta lorebook entry keeps the runtime state visible",
            10
        )
        onWindow.contextElements.add("beta keeps the runtime state visible")

        val onScanText = onWindow.buildLorebookScanText("Tell me about alpha.", useEntireContext = true)
        onWindow.selectAndTruncateContext(
            text = onScanText,
            totalTokenBudget = 200,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            fillMode = true
        )

        assertTrue(
            onWindow.loreBookKeys.containsKey("alpha"),
            "On + fillMode: the prompt-matched lorebook key must survive priority selection"
        )
        assertTrue(
            onWindow.loreBookKeys.containsKey("beta"),
            "On + fillMode: the contextElement-matched lorebook key must survive via the expanded scan (priority step or fill)"
        )
    }
}
