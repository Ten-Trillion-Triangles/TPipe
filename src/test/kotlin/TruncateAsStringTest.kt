package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test

private class TestPipeForTruncateAsString : Pipe() {
    override fun truncateModuleContext(): Pipe = this
    override suspend fun generateText(promptInjector: String): String = "test"

    fun setTestMiniBank(miniBank: MiniBank) {
        this.miniContextBank = miniBank
    }

    fun testWithTokenBudget(budget: TokenBudgetSettings, pageKeys: List<String>): Map<String, Int> {
        // Set the token budget which should apply truncateContextWindowAsString setting
        this.setTokenBudget(budget)
        
        // Use the internal allocation method that respects the budget settings
        val truncationSettings = this.getTruncationSettings()
        return calculateSizeBasedPriorityFill(budget.contextWindowSize ?: 1000, pageKeys, truncationSettings)
    }
}

class TruncateAsStringTest {

    @Test
    fun testTruncateAsStringVsElements() {
        val pipe = TestPipeForTruncateAsString()
        val miniBank = MiniBank()
        
        // Create contexts - same as before
        val smallContext = ContextWindow()
        smallContext.contextElements.add("Player health: 100")
        smallContext.contextElements.add("Player level: 5")
        smallContext.contextElements.add("Current weapon: sword")
        
        val mediumContext = ContextWindow()
        repeat(6) { i ->
            mediumContext.contextElements.add("Quest objective $i: Find the ancient artifact in the dungeon")
        }
        
        val largeContext = ContextWindow()
        repeat(20) { i ->
            largeContext.contextElements.add("Story chapter $i: The hero ventured forth into the mystical lands seeking adventure and glory")
        }
        
        miniBank.contextMap["gameplay"] = smallContext
        miniBank.contextMap["quests"] = mediumContext  
        miniBank.contextMap["story"] = largeContext
        
        pipe.setTestMiniBank(miniBank)
        
        println("=== TRUNCATE AS ELEMENTS (Default) ===")
        
        // Test 1: Default behavior (truncate as elements)
        val defaultBudget = TokenBudgetSettings(
            contextWindowSize = 1000,  // Total context window
            maxTokens = 200,           // Output tokens
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = false  // Default: treat as elements
        )
        
        val defaultAllocations = pipe.testWithTokenBudget(defaultBudget, listOf("gameplay", "quests", "story"))
        
        println("Budget: 200 tokens")
        println("Settings: truncateContextWindowAsString = false (elements)")
        println("Allocations:")
        defaultAllocations.forEach { (key, allocation) ->
            println("  $key: $allocation tokens")
        }
        println("Total: ${defaultAllocations.values.sum()}")
        
        println("\n=== TRUNCATE AS STRING ===")
        
        // Test 2: Truncate as string
        val stringBudget = TokenBudgetSettings(
            contextWindowSize = 1000,  // Total context window
            maxTokens = 200,           // Output tokens
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = true  // Enable string truncation
        )
        
        val stringAllocations = pipe.testWithTokenBudget(stringBudget, listOf("gameplay", "quests", "story"))
        
        println("Budget: 200 tokens")
        println("Settings: truncateContextWindowAsString = true (string)")
        println("Allocations:")
        stringAllocations.forEach { (key, allocation) ->
            println("  $key: $allocation tokens")
        }
        println("Total: ${stringAllocations.values.sum()}")
        
        println("\n=== COMPARISON ===")
        println("Story allocation difference:")
        val defaultStory = defaultAllocations["story"] ?: 0
        val stringStory = stringAllocations["story"] ?: 0
        println("  Elements mode: $defaultStory tokens")
        println("  String mode: $stringStory tokens")
        println("  Difference: ${stringStory - defaultStory} tokens")
        
        if (stringStory > defaultStory) {
            println("  ✅ String truncation preserves more story content!")
        } else if (stringStory == defaultStory) {
            println("  ⚠️ No difference between modes")
        } else {
            println("  ❌ String truncation preserves less content")
        }
        
        // Test with higher budget to see full effect
        println("\n=== HIGH BUDGET TEST (500 tokens) ===")
        
        val highBudgetElements = TokenBudgetSettings(
            contextWindowSize = 500,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = false
        )
        
        val highBudgetString = TokenBudgetSettings(
            contextWindowSize = 500,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = true
        )
        
        val highDefaultAllocations = pipe.testWithTokenBudget(highBudgetElements, listOf("gameplay", "quests", "story"))
        val highStringAllocations = pipe.testWithTokenBudget(highBudgetString, listOf("gameplay", "quests", "story"))
        
        println("Elements mode (500 budget):")
        highDefaultAllocations.forEach { (key, allocation) ->
            println("  $key: $allocation tokens")
        }
        
        println("String mode (500 budget):")
        highStringAllocations.forEach { (key, allocation) ->
            println("  $key: $allocation tokens")
        }
        
        val highDefaultStory = highDefaultAllocations["story"] ?: 0
        val highStringStory = highStringAllocations["story"] ?: 0
        println("Story preservation with high budget:")
        println("  Elements: $highDefaultStory tokens")
        println("  String: $highStringStory tokens")
        println("  String advantage: ${highStringStory - highDefaultStory} tokens")
    }
}
