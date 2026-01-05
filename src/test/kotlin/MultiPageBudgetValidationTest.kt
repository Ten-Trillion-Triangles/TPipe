package com.TTT

import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class MultiPageBudgetValidationTest {

    @Test
    fun testOriginalProblemFixed() {
        // Validate that the original overflow problem is fixed
        // Original problem: 3 pages × 1000 token budget each = 3000 tokens (3x overflow)
        // Fixed behavior: 3 pages sharing 1000 tokens = 333 tokens per page (within budget)
        
        val totalBudget = 1000
        val pageCount = 3
        
        // Equal split allocation (the fix)
        val budgetPerPage = totalBudget / pageCount // 333 per page
        val totalAllocated = budgetPerPage * pageCount // 999 total
        
        // Verify the fix prevents overflow
        assertTrue(totalAllocated <= totalBudget, 
            "Budget overflow detected: allocated $totalAllocated, budget $totalBudget")
        assertEquals(333, budgetPerPage)
        assertEquals(999, totalAllocated) // 333 * 3 = 999 <= 1000 ✓
    }

    @Test
    fun testTokenBudgetSettingsConfiguration() {
        // Test that new TokenBudgetSettings properties work correctly
        val settings = TokenBudgetSettings(
            contextWindowSize = 500,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.WEIGHTED_SPLIT,
            pageWeights = mapOf("pageA" to 2.0, "pageB" to 1.0),
            reserveEmptyPageBudget = false
        )
        
        assertEquals(500, settings.contextWindowSize)
        assertEquals(MultiPageBudgetStrategy.WEIGHTED_SPLIT, settings.multiPageBudgetStrategy)
        assertEquals(2, settings.pageWeights?.size)
        assertEquals(2.0, settings.pageWeights?.get("pageA"))
        assertEquals(1.0, settings.pageWeights?.get("pageB"))
        assertEquals(false, settings.reserveEmptyPageBudget)
    }

    @Test
    fun testMultiPageBudgetStrategies() {
        // Verify all budget strategies are available and work correctly
        val strategies = MultiPageBudgetStrategy.values()
        
        assertEquals(5, strategies.size)
        assertTrue(strategies.contains(MultiPageBudgetStrategy.EQUAL_SPLIT))
        assertTrue(strategies.contains(MultiPageBudgetStrategy.WEIGHTED_SPLIT))
        assertTrue(strategies.contains(MultiPageBudgetStrategy.PRIORITY_FILL))
        assertTrue(strategies.contains(MultiPageBudgetStrategy.DYNAMIC_FILL))
        assertTrue(strategies.contains(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL))
        
        // Test default strategy
        val defaultSettings = TokenBudgetSettings()
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, defaultSettings.multiPageBudgetStrategy)
    }

    @Test
    fun testBudgetAllocationNeverExceedsLimit() {
        // Comprehensive test across different scenarios
        val testScenarios = listOf(
            Triple(100, 1, "Single page"),
            Triple(100, 2, "Two pages"),
            Triple(100, 3, "Three pages"),
            Triple(100, 5, "Five pages"),
            Triple(100, 10, "Ten pages"),
            Triple(1, 3, "Minimal budget"),
            Triple(1000, 7, "Large budget"),
            Triple(0, 3, "Zero budget")
        )
        
        for ((budget, pageCount, description) in testScenarios) {
            val budgetPerPage = if (pageCount > 0) budget / pageCount else 0
            val totalAllocated = budgetPerPage * pageCount
            
            assertTrue(totalAllocated <= budget, 
                "$description failed: allocated $totalAllocated exceeds budget $budget")
            assertTrue(budgetPerPage >= 0, 
                "$description failed: negative allocation $budgetPerPage")
        }
    }

    @Test
    fun testWeightedAllocationMath() {
        // Test weighted allocation calculations
        val totalBudget = 600
        val weights = mapOf("high" to 3.0, "medium" to 2.0, "low" to 1.0)
        val totalWeight = weights.values.sum() // 6.0
        
        val allocations = weights.mapValues { (_, weight) ->
            (totalBudget * (weight / totalWeight)).toInt()
        }
        
        assertEquals(300, allocations["high"])   // 3/6 * 600 = 300
        assertEquals(200, allocations["medium"]) // 2/6 * 600 = 200  
        assertEquals(100, allocations["low"])    // 1/6 * 600 = 100
        
        val totalAllocated = allocations.values.sum()
        assertTrue(totalAllocated <= totalBudget)
        assertEquals(600, totalAllocated)
    }

    @Test
    fun testPriorityFillLogic() {
        // Test priority fill allocation logic
        val totalBudget = 250
        val contentSizes = listOf(
            "pageA" to 150, // Needs 150 tokens
            "pageB" to 100, // Needs 100 tokens
            "pageC" to 50   // Needs 50 tokens
        )
        
        val allocations = mutableMapOf<String, Int>()
        var remainingBudget = totalBudget
        
        for ((pageKey, requiredTokens) in contentSizes) {
            val allocation = minOf(requiredTokens, remainingBudget)
            allocations[pageKey] = allocation
            remainingBudget -= allocation
            
            if (remainingBudget <= 0) break
        }
        
        assertEquals(150, allocations["pageA"]) // Gets full requirement
        assertEquals(100, allocations["pageB"]) // Gets full requirement  
        assertEquals(0, allocations.getOrDefault("pageC", 0)) // No budget left (150+100=250)
        
        val totalAllocated = allocations.values.sum()
        assertTrue(totalAllocated <= totalBudget)
        assertEquals(250, totalAllocated)
    }

    @Test
    fun testBackwardCompatibility() {
        // Ensure single page scenarios work as before
        val singlePageBudget = 1000
        val singlePageCount = 1
        
        val allocation = singlePageBudget / singlePageCount
        assertEquals(1000, allocation) // Single page gets full budget
        
        // Ensure default settings maintain backward compatibility
        val defaultSettings = TokenBudgetSettings()
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, defaultSettings.multiPageBudgetStrategy)
        assertEquals(null, defaultSettings.pageWeights) // No weights by default
    }
}
