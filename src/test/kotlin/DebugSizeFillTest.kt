package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test

class DebugSizeFillTest {

    class TestPipe : Pipe() {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "test"
        
        fun testCalculateSizeBasedPriorityFill(
            totalBudget: Int,
            pageKeys: List<String>,
            truncationSettings: TruncationSettings
        ): Map<String, Int> {
            return calculateSizeBasedPriorityFill(totalBudget, pageKeys, truncationSettings)
        }
        
        fun testCountContextWindowTokens(contextWindow: ContextWindow, truncationSettings: TruncationSettings): Int {
            return countContextWindowTokens(contextWindow, truncationSettings)
        }
        
        fun setTestMiniBank(miniBank: MiniBank) {
            this.miniContextBank = miniBank
        }
    }

    @Test
    fun debugContextSizes() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Create contexts
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
        
        val truncationSettings = TruncationSettings()
        
        // Check actual sizes
        val gameplaySize = pipe.testCountContextWindowTokens(smallContext, truncationSettings)
        val questsSize = pipe.testCountContextWindowTokens(mediumContext, truncationSettings)
        val storySize = pipe.testCountContextWindowTokens(largeContext, truncationSettings)
        
        println("=== CONTEXT ANALYSIS ===")
        println("Gameplay context:")
        println("  Elements: ${smallContext.contextElements.size}")
        println("  Content: ${smallContext.contextElements}")
        println("  Calculated size: $gameplaySize tokens")
        println("  isEmpty(): ${smallContext.isEmpty()}")
        
        println("\nQuests context:")
        println("  Elements: ${mediumContext.contextElements.size}")
        println("  Content: ${mediumContext.contextElements.take(2)}")
        println("  Calculated size: $questsSize tokens")
        println("  isEmpty(): ${mediumContext.isEmpty()}")
        
        println("\nStory context:")
        println("  Elements: ${largeContext.contextElements.size}")
        println("  Content: ${largeContext.contextElements.take(2)}")
        println("  Calculated size: $storySize tokens")
        println("  isEmpty(): ${largeContext.isEmpty()}")
        
        // Test allocation
        val allocations = pipe.testCalculateSizeBasedPriorityFill(
            totalBudget = 100,
            pageKeys = listOf("gameplay", "quests", "story"),
            truncationSettings = truncationSettings
        )
        
        println("\n=== ALLOCATION RESULTS ===")
        println("Budget: 100 tokens")
        println("Allocations:")
        allocations.forEach { (key, allocation) ->
            println("  $key: $allocation tokens")
        }
        println("Total allocated: ${allocations.values.sum()}")
        
        // Check if story context is actually empty or has issues
        if (storySize == 0) {
            println("\n!!! STORY CONTEXT HAS 0 SIZE - INVESTIGATING !!!")
            println("Story context elements:")
            largeContext.contextElements.forEachIndexed { index, element ->
                println("  [$index]: '$element'")
            }
        }
    }
}
