package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deepCopy
import kotlin.test.Test

class TruncationBehaviorTest {

    class TestPipe : Pipe() {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "test"
        
        fun setTestMiniBank(miniBank: MiniBank) {
            this.miniContextBank = miniBank
        }
        
        // Duplicate the truncateMiniBank logic for testing
        fun testTruncateMiniBank(
            content: MultimodalContent,
            totalBudget: Int,
            budget: TokenBudgetSettings,
            truncationSettings: TruncationSettings
        ): Map<String, String> {
            val pageKeys = miniContextBank.contextMap.keys.toList()
            if(pageKeys.isEmpty()) return emptyMap()

            val pageBudgets = calculatePageBudgets(
                totalBudget,
                pageKeys,
                budget.multiPageBudgetStrategy,
                budget.pageWeights,
                truncationSettings,
                budget.reserveEmptyPageBudget
            )

            val results = mutableMapOf<String, String>()

            for((pageKey, contextWindow) in miniContextBank.contextMap) {
                val allocatedBudget = pageBudgets[pageKey] ?: 0
                if(allocatedBudget <= 0) {
                    results[pageKey] = "[EMPTY - No budget allocated]"
                    continue
                }

                // Create a copy to avoid modifying original
                val testWindow = contextWindow.deepCopy()
                
                // Apply truncation based on budget settings
                if(budget.truncateContextWindowAsString) {
                    // String truncation mode
                    val asString = testWindow.combineAndTruncateAsStringWithSettings(
                        content.text,
                        allocatedBudget,
                        truncationSettings,
                        budget.truncationMethod
                    )
                    results[pageKey] = "STRING MODE: $asString"
                } else {
                    // Element truncation mode (default)
                    testWindow.selectAndTruncateContext(
                        content.text,
                        allocatedBudget,
                        budget.truncationMethod,
                        truncationSettings,
                        fillMode = loreBookFillMode,
                        preserveTextMatches = budget.preserveTextMatches
                    )
                    
                    val elementResult = testWindow.contextElements.joinToString(" | ")
                    results[pageKey] = "ELEMENT MODE: $elementResult"
                }
            }

            return results
        }
    }

    @Test
    fun testStringVsElementTruncation() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Create contexts with different sizes
        val smallContext = ContextWindow()
        smallContext.contextElements.add("Player health: 100")
        smallContext.contextElements.add("Player level: 5")
        smallContext.contextElements.add("Current weapon: sword")
        
        val largeContext = ContextWindow()
        repeat(20) { i ->
            largeContext.contextElements.add("Story chapter $i: The hero ventured forth into the mystical lands seeking adventure and glory beyond imagination")
        }
        
        miniBank.contextMap["gameplay"] = smallContext
        miniBank.contextMap["story"] = largeContext
        
        pipe.setTestMiniBank(miniBank)
        
        val content = MultimodalContent("User input for context selection")
        
        println("=== ELEMENT TRUNCATION (Default) ===")
        
        // Test 1: Element truncation
        val elementBudget = TokenBudgetSettings(
            contextWindowSize = 1000,
            maxTokens = 200,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = false
        )
        
        val elementResults = pipe.testTruncateMiniBank(
            content, 150, elementBudget, TruncationSettings()
        )
        
        elementResults.forEach { (key, result) ->
            println("$key: $result")
        }
        
        println("\n=== STRING TRUNCATION ===")
        
        // Test 2: String truncation
        val stringBudget = TokenBudgetSettings(
            contextWindowSize = 1000,
            maxTokens = 200,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            truncateContextWindowAsString = true
        )
        
        val stringResults = pipe.testTruncateMiniBank(
            content, 150, stringBudget, TruncationSettings()
        )
        
        stringResults.forEach { (key, result) ->
            println("$key: $result")
        }
        
        println("\n=== COMPARISON ===")
        
        // Compare story preservation
        val elementStory = elementResults["story"] ?: ""
        val stringStory = stringResults["story"] ?: ""
        
        println("Story preservation comparison:")
        println("Element mode length: ${elementStory.length} chars")
        println("String mode length: ${stringStory.length} chars")
        
        if (stringStory.length > elementStory.length) {
            println("✅ String truncation preserves more content!")
        } else if (stringStory.length == elementStory.length) {
            println("⚠️ No difference in preservation")
        } else {
            println("❌ Element truncation preserves more content")
        }
        
        // Show actual content differences
        println("\nElement result preview:")
        println(elementStory.take(200) + "...")
        
        println("\nString result preview:")
        println(stringStory.take(200) + "...")
    }
}
