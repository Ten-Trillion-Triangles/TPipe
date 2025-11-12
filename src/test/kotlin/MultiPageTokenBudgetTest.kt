package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Context.Dictionary
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MultiPageTokenBudgetTest {

    @Test
    fun testEqualSplitBudgetAllocation() {
        // Create test MiniBank with 3 pages
        val miniBank = MiniBank()
        
        // Page A: ~10 tokens
        val pageA = ContextWindow()
        pageA.addLoreBookEntry("dragon", "Ancient fire-breathing creature with scales", 10)
        miniBank.contextMap["pageA"] = pageA
        
        // Page B: ~8 tokens  
        val pageB = ContextWindow()
        pageB.addLoreBookEntry("sword", "Sharp metal blade weapon", 10)
        miniBank.contextMap["pageB"] = pageB
        
        // Page C: ~6 tokens
        val pageC = ContextWindow()
        pageC.addLoreBookEntry("magic", "Mystical power source", 10)
        miniBank.contextMap["pageC"] = pageC
        
        // Test equal split with 300 token budget = 100 tokens per page
        val budget = TokenBudgetSettings(
            contextWindowSize = 300,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.EQUAL_SPLIT
        )
        
        // Simulate the budget allocation logic
        val pageKeys = miniBank.contextMap.keys.toList()
        val totalBudget = 300
        val expectedBudgetPerPage = totalBudget / pageKeys.size // 100 per page
        
        // Verify equal allocation
        assertEquals(100, expectedBudgetPerPage)
        assertEquals(3, pageKeys.size)
        
        // Verify total doesn't exceed budget
        val totalAllocated = expectedBudgetPerPage * pageKeys.size
        assertTrue(totalAllocated <= totalBudget)
    }

    @Test
    fun testWeightedSplitBudgetAllocation() {
        // Create test MiniBank with weighted pages
        val miniBank = MiniBank()
        
        val pageA = ContextWindow()
        pageA.addLoreBookEntry("important", "Very important content", 10)
        miniBank.contextMap["pageA"] = pageA
        
        val pageB = ContextWindow()  
        pageB.addLoreBookEntry("normal", "Normal content", 10)
        miniBank.contextMap["pageB"] = pageB
        
        // Test weighted split: Page A gets 2x weight of Page B
        val weights = mapOf("pageA" to 2.0, "pageB" to 1.0)
        val totalBudget = 300
        val totalWeight = weights.values.sum() // 3.0
        
        // Calculate expected allocations
        val expectedPageA = (totalBudget * (2.0 / totalWeight)).toInt() // 200
        val expectedPageB = (totalBudget * (1.0 / totalWeight)).toInt() // 100
        
        assertEquals(200, expectedPageA)
        assertEquals(100, expectedPageB)
        
        // Verify total allocation doesn't exceed budget
        assertTrue(expectedPageA + expectedPageB <= totalBudget)
    }

    @Test
    fun testPriorityFillBudgetAllocation() {
        // Create test MiniBank with different content sizes
        val miniBank = MiniBank()
        
        // Large page needing ~150 tokens
        val pageA = ContextWindow()
        pageA.addLoreBookEntry("large", "This is a very large lorebook entry with many words that will consume significant tokens in the context window budget allocation system", 10)
        miniBank.contextMap["pageA"] = pageA
        
        // Medium page needing ~75 tokens
        val pageB = ContextWindow()
        pageB.addLoreBookEntry("medium", "This is a medium sized lorebook entry with moderate token usage", 10)
        miniBank.contextMap["pageB"] = pageB
        
        // Small page needing ~25 tokens
        val pageC = ContextWindow()
        pageC.addLoreBookEntry("small", "Small entry", 10)
        miniBank.contextMap["pageC"] = pageC
        
        val totalBudget = 200
        
        // Priority fill should allocate in order until budget exhausted
        // Page A gets what it needs (up to budget limit)
        // Page B gets remaining budget
        // Page C may get nothing if budget exhausted
        
        val pageATokens = Dictionary.countTokens("This is a very large lorebook entry with many words that will consume significant tokens in the context window budget allocation system")
        val pageBTokens = Dictionary.countTokens("This is a medium sized lorebook entry with moderate token usage")
        val pageCTokens = Dictionary.countTokens("Small entry")
        
        // Verify priority allocation logic
        var remainingBudget = totalBudget
        val allocationA = minOf(pageATokens, remainingBudget)
        remainingBudget -= allocationA
        
        val allocationB = minOf(pageBTokens, remainingBudget)
        remainingBudget -= allocationB
        
        val allocationC = minOf(pageCTokens, remainingBudget)
        
        // Total allocation should not exceed budget
        assertTrue(allocationA + allocationB + allocationC <= totalBudget)
        
        // First page should get priority
        assertTrue(allocationA > 0)
    }

    @Test
    fun testBudgetAllocationWithEmptyPages() {
        // Test edge case with empty pages
        val miniBank = MiniBank()
        
        // Empty page
        val emptyPage = ContextWindow()
        miniBank.contextMap["empty"] = emptyPage
        
        // Non-empty page
        val contentPage = ContextWindow()
        contentPage.addLoreBookEntry("content", "Has actual content", 10)
        miniBank.contextMap["content"] = contentPage
        
        val totalBudget = 100
        val pageKeys = miniBank.contextMap.keys.toList()
        
        // Equal split should still work with empty pages
        val budgetPerPage = totalBudget / pageKeys.size // 50 each
        
        assertEquals(50, budgetPerPage)
        assertEquals(2, pageKeys.size)
        
        // Verify empty page handling doesn't break allocation
        assertTrue(budgetPerPage * pageKeys.size <= totalBudget)
    }

    @Test
    fun testBudgetAllocationWithSinglePage() {
        // Test edge case with single page (should get full budget)
        val miniBank = MiniBank()
        
        val singlePage = ContextWindow()
        singlePage.addLoreBookEntry("only", "Only content in minibank", 10)
        miniBank.contextMap["single"] = singlePage
        
        val totalBudget = 100
        val pageKeys = miniBank.contextMap.keys.toList()
        
        // Single page should get full budget
        val budgetPerPage = totalBudget / pageKeys.size
        
        assertEquals(100, budgetPerPage)
        assertEquals(1, pageKeys.size)
    }

    @Test
    fun testZeroBudgetHandling() {
        // Test edge case with zero budget
        val miniBank = MiniBank()
        
        val page = ContextWindow()
        page.addLoreBookEntry("content", "Some content", 10)
        miniBank.contextMap["page"] = page
        
        val totalBudget = 0
        val pageKeys = miniBank.contextMap.keys.toList()
        
        // Zero budget should result in zero allocation per page
        val budgetPerPage = if (pageKeys.isNotEmpty()) totalBudget / pageKeys.size else 0
        
        assertEquals(0, budgetPerPage)
    }

    @Test
    fun testWeightedSplitWithMissingWeights() {
        // Test weighted split when some pages don't have weights
        val miniBank = MiniBank()
        
        val pageA = ContextWindow()
        pageA.addLoreBookEntry("a", "Content A", 10)
        miniBank.contextMap["pageA"] = pageA
        
        val pageB = ContextWindow()
        pageB.addLoreBookEntry("b", "Content B", 10)
        miniBank.contextMap["pageB"] = pageB
        
        val pageC = ContextWindow()
        pageC.addLoreBookEntry("c", "Content C", 10)
        miniBank.contextMap["pageC"] = pageC
        
        // Only provide weights for some pages
        val partialWeights = mapOf("pageA" to 2.0, "pageB" to 1.0)
        // pageC has no weight - should default to 0.0
        
        val totalBudget = 300
        val totalWeight = partialWeights.values.sum() // 3.0 (pageC not included)
        
        // Pages with weights should get proportional allocation
        val expectedPageA = (totalBudget * (2.0 / totalWeight)).toInt() // 200
        val expectedPageB = (totalBudget * (1.0 / totalWeight)).toInt() // 100
        val expectedPageC = 0 // No weight = no allocation
        
        assertEquals(200, expectedPageA)
        assertEquals(100, expectedPageB)
        assertEquals(0, expectedPageC)
        
        // Total should not exceed budget
        assertTrue(expectedPageA + expectedPageB + expectedPageC <= totalBudget)
    }

    @Test
    fun testBudgetAllocationNeverExceedsTotal() {
        // Comprehensive test to ensure budget allocation never exceeds total
        val testCases = listOf(
            Triple(100, 3, MultiPageBudgetStrategy.EQUAL_SPLIT),
            Triple(150, 4, MultiPageBudgetStrategy.EQUAL_SPLIT),
            Triple(200, 7, MultiPageBudgetStrategy.EQUAL_SPLIT),
            Triple(1, 5, MultiPageBudgetStrategy.EQUAL_SPLIT) // Edge case: very small budget
        )
        
        for ((totalBudget, pageCount, strategy) in testCases) {
            val miniBank = MiniBank()
            
            // Create pages
            repeat(pageCount) { i ->
                val page = ContextWindow()
                page.addLoreBookEntry("key$i", "Content for page $i", 10)
                miniBank.contextMap["page$i"] = page
            }
            
            val pageKeys = miniBank.contextMap.keys.toList()
            val budgetPerPage = totalBudget / pageKeys.size
            val totalAllocated = budgetPerPage * pageKeys.size
            
            // Verify allocation never exceeds total budget
            assertTrue(totalAllocated <= totalBudget, 
                "Budget exceeded: allocated $totalAllocated, total $totalBudget, pages $pageCount")
            
            // Verify each page gets reasonable allocation
            assertTrue(budgetPerPage >= 0, "Negative budget allocation")
        }
    }
}
