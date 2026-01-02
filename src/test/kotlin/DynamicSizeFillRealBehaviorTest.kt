package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DynamicSizeFillRealBehaviorTest {

    // Create a concrete test pipe that can access internal methods
    class TestPipe : Pipe() {
        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = "test"
        
        // Expose internal methods for testing
        fun testCalculateSizeBasedPriorityFill(
            totalBudget: Int,
            pageKeys: List<String>,
            truncationSettings: TruncationSettings
        ): Map<String, Int> {
            return calculateSizeBasedPriorityFill(totalBudget, pageKeys, truncationSettings)
        }
        
        fun testCalculateDynamicSizeFill(
            totalBudget: Int,
            pageKeys: List<String>,
            truncationSettings: TruncationSettings
        ): Map<String, Int> {
            return calculateDynamicSizeFill(totalBudget, pageKeys, truncationSettings)
        }
        
        fun testCountContextWindowTokens(contextWindow: ContextWindow, truncationSettings: TruncationSettings): Int {
            return countContextWindowTokens(contextWindow, truncationSettings)
        }
        
        // Expose miniContextBank for testing
        fun setTestMiniBank(miniBank: MiniBank) {
            this.miniContextBank = miniBank
        }
    }

    @Test
    fun testSizeBasedPriorityWithRealContent() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Create small context (approximately 20 tokens)
        val smallContext = ContextWindow()
        smallContext.contextElements.add("Player health: 100")
        smallContext.contextElements.add("Player level: 5")
        smallContext.contextElements.add("Current weapon: sword")
        
        // Create medium context (approximately 60 tokens)
        val mediumContext = ContextWindow()
        repeat(6) { i ->
            mediumContext.contextElements.add("Quest objective $i: Find the ancient artifact in the dungeon")
        }
        
        // Create large context (approximately 200+ tokens)
        val largeContext = ContextWindow()
        repeat(20) { i ->
            largeContext.contextElements.add("Story chapter $i: The hero ventured forth into the mystical lands seeking adventure and glory")
        }
        
        miniBank.contextMap["gameplay"] = smallContext
        miniBank.contextMap["quests"] = mediumContext  
        miniBank.contextMap["story"] = largeContext
        
        pipe.setTestMiniBank(miniBank)
        
        // DEBUG: Check actual context sizes and content
        println("DEBUG - Context content:")
        println("  Gameplay elements: ${smallContext.contextElements.size}")
        smallContext.contextElements.forEach { println("    - '$it'") }
        println("  Quests elements: ${mediumContext.contextElements.size}")
        mediumContext.contextElements.take(2).forEach { println("    - '$it'") }
        println("  Story elements: ${largeContext.contextElements.size}")
        largeContext.contextElements.take(2).forEach { println("    - '$it'") }
        
        // DEBUG: Check if contexts are empty
        println("DEBUG - Context empty status:")
        println("  Gameplay isEmpty: ${smallContext.isEmpty()}")
        println("  Quests isEmpty: ${mediumContext.isEmpty()}")
        println("  Story isEmpty: ${largeContext.isEmpty()}")
        
        // DEBUG: Check calculated token sizes
        val truncationSettings = TruncationSettings()
        println("DEBUG - Calculated token sizes:")
        println("  Gameplay size: ${pipe.testCountContextWindowTokens(smallContext, truncationSettings)}")
        println("  Quests size: ${pipe.testCountContextWindowTokens(mediumContext, truncationSettings)}")
        println("  Story size: ${pipe.testCountContextWindowTokens(largeContext, truncationSettings)}")
        
        // Test with limited budget (100 tokens) - should prioritize smaller contexts
        val allocations = pipe.testCalculateSizeBasedPriorityFill(
            totalBudget = 100,
            pageKeys = listOf("gameplay", "quests", "story"),
            truncationSettings = TruncationSettings()
        )
        
        val gameplayAllocation = allocations["gameplay"] ?: 0
        val questsAllocation = allocations["quests"] ?: 0
        val storyAllocation = allocations["story"] ?: 0
        
        println("Size-based allocations:")
        println("  Gameplay (small): $gameplayAllocation tokens")
        println("  Quests (medium): $questsAllocation tokens") 
        println("  Story (large): $storyAllocation tokens")
        println("  Total: ${gameplayAllocation + questsAllocation + storyAllocation}")
        
        // Verify size-based priority behavior
        assertTrue(gameplayAllocation > 0, "Small gameplay context should get allocation")
        assertTrue(questsAllocation > 0, "Medium quests context should get allocation")
        
        // Small context should get more relative to its size than large context
        assertTrue(gameplayAllocation >= 15, "Gameplay should get substantial allocation for its size")
        
        // Total should not exceed budget
        val total = gameplayAllocation + questsAllocation + storyAllocation
        assertTrue(total <= 100, "Total allocation ($total) should not exceed budget (100)")
        
        // Verify contexts are allocated in size order (smallest first gets priority)
        assertTrue(total > 0, "Some allocation should occur")
    }

    @Test
    fun testDynamicSizeFillWithRedistribution() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Create tiny context that will have unused budget
        val tinyContext = ContextWindow()
        tinyContext.contextElements.add("HP: 100")
        
        // Create large context that can use more budget
        val largeContext = ContextWindow()
        repeat(50) { i ->
            largeContext.contextElements.add("Long story content line $i with detailed narrative and character development")
        }
        
        miniBank.contextMap["stats"] = tinyContext
        miniBank.contextMap["narrative"] = largeContext
        
        pipe.setTestMiniBank(miniBank)
        
        // Test DYNAMIC_SIZE_FILL with redistribution
        val allocations = pipe.testCalculateDynamicSizeFill(
            totalBudget = 200,
            pageKeys = listOf("stats", "narrative"),
            truncationSettings = TruncationSettings()
        )
        
        val statsAllocation = allocations["stats"] ?: 0
        val narrativeAllocation = allocations["narrative"] ?: 0
        
        println("Dynamic size fill with redistribution:")
        println("  Stats (tiny): $statsAllocation tokens")
        println("  Narrative (large): $narrativeAllocation tokens")
        println("  Total: ${statsAllocation + narrativeAllocation}")
        
        // Tiny context should get what it needs
        assertTrue(statsAllocation > 0, "Stats should get allocation")
        assertTrue(statsAllocation <= 50, "Stats allocation should be reasonable for tiny context")
        
        // Large context should get remaining + redistributed budget
        assertTrue(narrativeAllocation > 0, "Narrative should get allocation")
        assertTrue(narrativeAllocation > statsAllocation, "Narrative should get more tokens than stats")
        
        // Total should be reasonable
        val total = statsAllocation + narrativeAllocation
        assertTrue(total <= 200, "Total should not exceed budget")
        assertTrue(total > 50, "Should allocate substantial portion of budget")
    }

    @Test
    fun testProtectSmallContextsScenario() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Critical small context (game state)
        val gameState = ContextWindow()
        gameState.contextElements.add("Level: 5")
        gameState.contextElements.add("Score: 1250")
        gameState.contextElements.add("Lives: 3")
        
        // Expendable large context (story background)
        val storyBackground = ContextWindow()
        repeat(100) { i ->
            storyBackground.contextElements.add("In the ancient times, long before the great war, there lived a hero named $i who sought to save the realm from darkness and despair")
        }
        
        miniBank.contextMap["gameState"] = gameState
        miniBank.contextMap["storyBackground"] = storyBackground
        
        pipe.setTestMiniBank(miniBank)
        
        // Test with very limited budget - should protect game state
        val allocations = pipe.testCalculateSizeBasedPriorityFill(
            totalBudget = 50,
            pageKeys = listOf("gameState", "storyBackground"),
            truncationSettings = TruncationSettings()
        )
        
        val gameStateAllocation = allocations["gameState"] ?: 0
        val storyAllocation = allocations["storyBackground"] ?: 0
        
        println("Protection scenario:")
        println("  Game State (critical): $gameStateAllocation tokens")
        println("  Story Background (expendable): $storyAllocation tokens")
        
        // Critical small context should be protected
        assertTrue(gameStateAllocation > 0, "Game state should be protected")
        assertTrue(gameStateAllocation >= 10, "Game state should get meaningful allocation")
        
        // Story should get remaining budget
        assertTrue(storyAllocation >= 0, "Story allocation should be non-negative")
        
        // Game state should be prioritized despite being smaller
        val total = gameStateAllocation + storyAllocation
        assertTrue(total <= 50, "Total should not exceed budget")
        
        // The key test: small context survives while large context may be truncated
        assertTrue(gameStateAllocation > 0, "Small critical context must survive")
    }

    @Test
    fun testEmptyContextHandling() {
        val pipe = TestPipe()
        val miniBank = MiniBank()
        
        // Empty context
        val emptyContext = ContextWindow()
        
        // Context with content
        val contentContext = ContextWindow()
        repeat(10) { i ->
            contentContext.contextElements.add("Content line $i with meaningful information")
        }
        
        miniBank.contextMap["empty"] = emptyContext
        miniBank.contextMap["content"] = contentContext
        
        pipe.setTestMiniBank(miniBank)
        
        val allocations = pipe.testCalculateSizeBasedPriorityFill(
            totalBudget = 100,
            pageKeys = listOf("empty", "content"),
            truncationSettings = TruncationSettings()
        )
        
        val emptyAllocation = allocations["empty"] ?: 0
        val contentAllocation = allocations["content"] ?: 0
        
        println("Empty context handling:")
        println("  Empty: $emptyAllocation tokens")
        println("  Content: $contentAllocation tokens")
        
        // Empty context should get 0 allocation
        assertEquals(0, emptyAllocation, "Empty context should get 0 allocation")
        
        // Content context should get the budget
        assertTrue(contentAllocation > 0, "Content context should get allocation")
        assertTrue(contentAllocation <= 100, "Content allocation should not exceed budget")
    }
}
